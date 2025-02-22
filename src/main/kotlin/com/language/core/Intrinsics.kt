package com.language.core

import com.language.TemplatedType
import com.language.compilation.*
import com.language.compilation.metadata.MetaDataHandle
import com.language.compilation.metadata.MetaDataTypeHandle
import com.language.compilation.tracking.BroadForge
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
            val forge = variables.getForge("object") as StructInstanceForge
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

    }
)