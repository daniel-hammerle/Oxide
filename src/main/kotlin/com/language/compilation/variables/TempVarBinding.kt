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
import com.language.compilation.tracking.InstanceForge
import java.util.*

class TempVarBinding(private val variable: TempVariable,
                     override var forge: InstanceForge
): VariableProvider {

    override fun get(parent: VariableMapping): TypedInstruction {
         return TypedInstruction.LoadVar(variable.id, forge)
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        forge = value.forge
        val id = parent.changeVar(variable.id, value)
        if (id != null) {
            error("Invalid type temp variable cannot change type sizes")
        }
        return TypedInstruction.StoreVar(variable.id, value)
    }

    override fun type(parent: VariableMapping): Type {
        return parent.getType(variable.id)
    }

    override fun clone(
        forges: MutableMap<UUID, InstanceForge>,
        providers: IdentityHashMap<VariableProvider, VariableProvider>
    ): VariableProvider {
        providers[this]?.let { return it }
        return TempVarBinding(variable, forge.clone(forges)).also { providers[this] = it }
    }


}