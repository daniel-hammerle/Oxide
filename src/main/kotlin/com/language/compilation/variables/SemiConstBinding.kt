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

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.generateName
import com.language.compilation.tracking.InstanceForge
import java.util.*

class SemiConstBinding(
    var value: TypedInstruction.Const,
    override var forge: InstanceForge
) : VariableProvider, AllocationRecord {
    @Volatile
    private var id: Int? = null

    override fun get(parent: VariableMapping): TypedInstruction {
        return synchronized(this) {
            when(val id = id) {
                is Int -> TypedInstruction.LoadVar(id, forge)
                else -> value
            }
        }
    }


    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        this.forge = value.forge
        if (allocated && value is TypedInstruction.Const) {
            //if we had a physical allocation, we can now remove it since we now have a constant value
            if (id != null) {
                parent.deleteVar(id!!)
                id = null
            }
            this.value = value
            return TypedInstruction.Noop(Type.Nothing)
        }
        val ins = synchronized(this) {
            when(val i = id) {
                is Int -> {
                    val id = parent.changeVar(i, value)
                    if (id != null) {
                        this.id = id
                    }
                    return TypedInstruction.StoreVar(this.id!!, value)
                }
                else -> {
                    this.id = parent.new(value.type)
                    return TypedInstruction.StoreVar(id!!, value)
                }
            }
        }
        return ins
    }

    override fun delete(parent: VariableMapping) {
        when(val id = id) {
            is Int -> parent.deleteVar(id)
            else -> {}
        }
    }

    override val physicalId: Int? = id
    override fun clone(
        forges: MutableMap<UUID, InstanceForge>,
        providers: IdentityHashMap<VariableProvider, VariableProvider>
    ): VariableProvider {
        providers[this]?.let { return it }
        return SemiConstBinding(value, forge.clone(forges)).also {
            it.value = this.value
            providers[this] = it
        }

    }

    override val allocated: Boolean
        get() = id != null
}