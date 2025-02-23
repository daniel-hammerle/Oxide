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
package com.language.compilation.variables

import com.language.compilation.TypedInstruction
import com.language.compilation.tracking.InstanceForge
import java.util.*

class VariableBinding(var id: Int, override var forge: InstanceForge) : VariableProvider {

    companion object {
        fun forValue(value: TypedInstruction, parent: VariableMapping): Pair<TypedInstruction, VariableBinding> {
            val id = parent.new(value.type)
            return TypedInstruction.StoreVar(id, value) to VariableBinding(id, value.forge)
        }

        fun ofType(forge: InstanceForge, parent: VariableMapping): VariableBinding {
            val id = parent.new(forge.type)
            return VariableBinding(id, forge)
        }
    }

    override fun get(parent: VariableMapping): TypedInstruction {
        return TypedInstruction.LoadVar(id, forge)
    }

    override fun delete(parent: VariableMapping) {
        parent.deleteVar(id)
    }

    override val physicalId: Int = id
    override fun clone(
        forges: MutableMap<UUID, InstanceForge>,
        providers: IdentityHashMap<VariableProvider, VariableProvider>
    ): VariableProvider {
        providers[this]?.let { return it }

        return VariableBinding(id, forge.clone(forges)).also { providers[this] = it }
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        this.forge = value.forge
        val newId = parent.changeVar(id, value)
        if (newId != null) {
            id = newId
        }
        return TypedInstruction.StoreVar(id, value)
    }
}