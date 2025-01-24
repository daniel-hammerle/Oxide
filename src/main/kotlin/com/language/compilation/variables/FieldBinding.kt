package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.isContainedOrEqualTo
import com.language.compilation.tracking.InstanceForge
import java.util.UUID

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

    override fun clone(forges: MutableMap<UUID, InstanceForge>) = FieldBinding(instance, forge.clone(forges), type, name, physicalType)
}