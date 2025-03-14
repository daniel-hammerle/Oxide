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
package com.language.core

import com.language.TemplatedType
import com.language.compilation.*
import com.language.compilation.metadata.MetaDataHandle
import com.language.compilation.metadata.MetaDataTypeHandle
import com.language.compilation.tracking.BroadForge
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.StructInstanceForge
import com.language.compilation.variables.TypeVariableManager
import com.language.compilation.variables.VariableManager
import com.language.eval.value
import com.language.lexer.MetaInfo
import com.language.lookup.IRLookup

val IntrinsicMemberFunctions: Map<Triple<SignatureString, TemplatedType, String>, Instruction.Intrinsic> = mapOf(
    Triple(SignatureString("std::types"), TemplatedType.Complex(SignatureString("std::types::Field"), emptyList()),"get") to object : Instruction.Intrinsic() {
        override val info: MetaData = MetaData(MetaInfo(0), SignatureString("std::types"))

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val obj = variables.loadVar("self")
            if (obj !is TypedInstruction.ConstObject) {
                error("Can only use the get intrinsic function on constant objects but got $obj")
            }
            val name = obj.fields.first { it.first == "name" }.second.value() as String
            val forge = variables.getForge("object") as? StructInstanceForge ?: error(variables.getForge("object"))
            if (name !in forge.members) {
                error("${forge.members} does not support field $name")
            }
            val childForge = forge.memberForge(name)!!
            val tp = lookup.lookUpPhysicalFieldType(forge.type, name)

            return TypedInstruction.DynamicPropertyAccess(variables.loadVar("object"), name, childForge, tp)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): BroadForge {
            TODO("Not yet implemented")
        }

    },
)

val IntrinsicFunctions: Map<Pair<SignatureString, String>, Instruction.Intrinsic> = mapOf(
    SignatureString("std::io") to "puts" to object : Instruction.Intrinsic() {
        override val info: MetaData = MetaData(MetaInfo(0), SignatureString("std::io"))

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return TypedInstruction.PlatformSpecificOperation(NativeOperationKind.Puts, listOf(variables.loadVar("value")), InstanceForge.ConstString)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): BroadForge {
            return InstanceForge.ConstString
        }

    }
)