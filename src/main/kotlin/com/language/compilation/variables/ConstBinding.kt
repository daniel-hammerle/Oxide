package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.tracking.InstanceForge
import java.util.UUID

class ConstBinding<T: TypedInstruction.Const>(
    val value: T
) : VariableProvider {
    override val forge: InstanceForge
        get() = value.forge

    override fun clone(forges: MutableMap<UUID, InstanceForge>): VariableProvider = this
    override fun get(parent: VariableMapping): T = value

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        error("Cannot change const binding")
    }

}