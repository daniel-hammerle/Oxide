// Copyright 2025 Daniel Hammerle
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.language.compilation.tracking

import com.language.codegen.cloneAll
import com.language.codegen.enumerate
import com.language.compilation.Type
import com.language.compilation.join
import java.util.UUID

class JoinedInstanceForge(var forges: List<InstanceForge>, override val id: UUID = UUID.randomUUID()) : InstanceForge, MemberChangeable {
    override val type: Type = forges.fold<InstanceForge, Type>(Type.Never) { acc, it -> acc.join(it.type) }
    override fun clone(processes: MutableMap<UUID, InstanceForge>): InstanceForge {
        return processes[id] ?: JoinedInstanceForge(forges.map { it.clone(processes) }, id).also { processes[id] = it }
    }

    override fun referenceAll(
        references: MutableMap<UUID, List<InstanceAccessInstruction>>,
        prev: List<InstanceAccessInstruction>
    ) {
        references[id] = prev
        forges.map { it.type }
    }

    override fun compare(other: InstanceForge, instanceLookup: InstanceLookup): InstanceChange {
        if (other.id == id) {
            other as JoinedInstanceForge
            return InstanceChange.UnionChanges(forges.zip(other.forges).map { (it, other) -> it.compare(other, instanceLookup) })
        }
        if (other is JoinedInstanceForge && forges.all { it in other.forges }) {
             return InstanceChange.Unionization(other.forges.filter { it !in forges }.map { it.toTemplate(instanceLookup) })
        }

        return InstanceChange.New(other.toTemplate(instanceLookup))
    }

    override fun toTemplate(instanceLookup: InstanceLookup): InstanceBuilder {
        instanceLookup.find(id)?.let { return it }

        return InstanceBuilder.New(InstanceTemplate.Joined(forges.map { it.toTemplate(instanceLookup) }))
    }

    override fun mergeMut(others: List<InstanceForge>): InstanceForge {
        val same = others.filter { it.id == id }.map { it as JoinedInstanceForge }
        for ((i, member) in this.forges.enumerate()) {
            member.mergeMut(same.map { it.forges[i] })
        }

        return others.filter { it.id != id }.fold(this as InstanceForge) { acc, it -> acc.join(it) }
    }

    override fun definiteChange(name: String, forge: InstanceForge) {
        possibleChange(name, forge)

        //locally insert a smart cast definite change
        forges = forges.cloneAll().onEach { (it as MemberChangeable).definiteChange(name, forge) }

    }

    override fun possibleChange(name: String, forge: InstanceForge) {
        forges.forEach { (it as MemberChangeable).possibleChange(name, forge) }
    }
}