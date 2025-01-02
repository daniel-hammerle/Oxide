package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.isContainedOrEqualTo

class FieldBinding(
    val instance: TypedInstruction,
    val type: Type,
    val name: String
) : VariableProvider {
    override fun get(parent: VariableMapping): TypedInstruction {
        return TypedInstruction.DynamicPropertyAccess(
            instance,
            name,
            type,
            type
        )
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        if (!value.type.isContainedOrEqualTo(type)) error("Underlying variable implementation cannot handle type ${value.type} (underlying type is $type)")
        return TypedInstruction.DynamicPropertyAssignment(
            instance,
            name,
            value
        )
    }

    override fun clone(): VariableProvider = FieldBinding(instance, type, name)
    override fun genericChangeRequest(parent: VariableMapping, genericName: String, type: Type) {
        TODO("Not yet implemented")
    }

}