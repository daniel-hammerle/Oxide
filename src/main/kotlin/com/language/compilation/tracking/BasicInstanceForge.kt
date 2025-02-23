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

import com.language.compilation.Type
import java.util.UUID

data class BasicInstanceForge(override val type: Type, override val id: UUID = UUID.randomUUID()) : InstanceForge {

    override fun clone(processes: MutableMap<UUID, InstanceForge>): InstanceForge {
        return processes[id] ?: BasicInstanceForge(type, id).also { processes[id] = it }
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
        if (other.id == id) {
            return InstanceChange.Nothing
        }

        return InstanceChange.New(other.toTemplate(instanceLookup))
    }

    override fun toTemplate(instanceLookup: InstanceLookup): InstanceBuilder {
        return InstanceBuilder.New(InstanceTemplate.Const(this))
    }

    override fun mergeMut(others: List<InstanceForge>): InstanceForge {
        return others.filter { it.id != id }.fold(this as InstanceForge) { acc, it -> acc.join(it)  }
    }

}