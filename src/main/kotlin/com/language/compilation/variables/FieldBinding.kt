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
import com.language.compilation.isContainedOrEqualTo
import com.language.compilation.tracking.InstanceForge
import java.util.*

class FieldBinding(
    val instance: TypedInstruction,
    override var forge: InstanceForge,
    val type: Type,
    val name: String,
    val physicalType: Type
) : VariableProvider {
    override fun get(parent: VariableMapping): TypedInstruction {
        return TypedInstruction.DynamicPropertyAccess(
            instance,
            name,
            forge,
            type
        )
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        if (!value.type.isContainedOrEqualTo(type)) error("Underlying variable implementation cannot handle type ${value.type} (underlying type is $type)")
        this.forge = value.forge
        return TypedInstruction.DynamicPropertyAssignment(
            instance,
            name,
            value,
            physicalType
        )
    }

    override fun clone(
        forges: MutableMap<UUID, InstanceForge>,
        providers: IdentityHashMap<VariableProvider, VariableProvider>
    ): VariableProvider {
        providers[this]?.let { return it }
        return FieldBinding(instance, forge.clone(forges), type, name, physicalType).also { providers[this] = it }
    }

}