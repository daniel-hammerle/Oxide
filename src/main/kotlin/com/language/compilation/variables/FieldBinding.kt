package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.isContainedOrEqualTo

class FieldBinding(
    val instance: TypedInstruction,
    val type: Type,
    val name: String
) : VariableProvider {
    override fun get(): TypedInstruction {
        return TypedInstruction.DynamicPropertyAccess(
            instance,
            name,
            type
        )
    }

    override fun put(value: TypedInstruction): TypedInstruction {
        if (!value.type.isContainedOrEqualTo(type)) error("Underlying variable implementation cannot handle type ${value.type} (underlying type is $type)")
        return TypedInstruction.DynamicPropertyAssignment(
            instance,
            name,
            value
        )
    }
}