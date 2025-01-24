package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.tracking.InstanceForge
import java.util.UUID

//Special Variable Provider for tests that allows to simulate variables that are not backed by anything
data class PhantomBinding(var type: Type): VariableProvider {
    override val forge: InstanceForge = InstanceForge.make(type)
    override fun get(parent: VariableMapping): TypedInstruction {
        return TypedInstruction.Noop(type)
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        type = value.type
        return TypedInstruction.Noop(Type.Nothing)
    }

    override fun clone(forges: MutableMap<UUID, InstanceForge>): VariableProvider {
        return this
    }

}