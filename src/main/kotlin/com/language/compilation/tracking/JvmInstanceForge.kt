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

import com.language.compilation.History
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.join
import java.util.UUID

class JvmInstanceForge(
    val generics: MutableMap<String, InstanceForge>,
    val fullSignature: SignatureString,
    override val id: UUID = UUID.randomUUID(),
) : InstanceForge {
    override val type: Type
        get() = Type.BasicJvmType(fullSignature, genericTypes)

    val genericTypes: Map<String, Type>
        get() = generics.mapValues { it.value.type }

    override fun clone(processes: MutableMap<UUID, InstanceForge>): InstanceForge {
        return processes[id] ?: JvmInstanceForge(generics.toMutableMap(), fullSignature, id)
    }

    override fun referenceAll(
        references: MutableMap<UUID, List<InstanceAccessInstruction>>,
        prev: List<InstanceAccessInstruction>
    ) {
        references[id] = prev
    }


    override fun compare(
        other: InstanceForge,
        instanceLookup: InstanceLookup
    ): InstanceChange {
        if (id == other.id) {
            other as JvmInstanceForge
            return InstanceChange.JvmChange(generics.mapValues { it.value.toTemplate(instanceLookup) })
        }

        if (other is JoinedInstanceForge && this in other.forges) {
            return InstanceChange.Unionization(other.forges.filter { it != this }.map { it.toTemplate(instanceLookup) })
        }

        return InstanceChange.New(other.toTemplate(instanceLookup))
    }

    override fun toTemplate(instanceLookup: InstanceLookup): InstanceBuilder {
        instanceLookup.find(id)?.let { return it }

        return InstanceBuilder.New(InstanceTemplate.Jvm(fullSignature, generics.mapValues { it.value.toTemplate(instanceLookup) }))
    }

    fun applyChanges(changes: Map<String, InstanceForge>) {
        changes.forEach { (name, forge) ->
            if (name !in generics) return@forEach
            generics[name] = generics[name]!!.join(forge)
        }
    }

    override fun mergeMut(others: List<InstanceForge>): InstanceForge {
        val same = others.filter { it.id == this.id }.map { it as JvmInstanceForge }

        for ((name, type) in this.generics) {
            generics[name] = same.map { it.generics[name]!! }.fold(type) { acc, it -> acc.join(it)  }
        }

        return others.filter { it.id != this.id }.fold(this as InstanceForge) { acc, it -> acc.join(it) }
    }

    override fun reference() {
        //Gracefully ignore
    }

    override suspend fun drop(droppingHistory: History) {
        //Gracefully ignore

    }
}