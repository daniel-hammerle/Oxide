package com.language.compilation.variables

import com.language.compilation.TypedInstruction
import com.language.compilation.tracking.ArrayInstanceForge
import com.language.compilation.tracking.InstanceForge
import java.util.*

class ArrayItemBinding(
    val array: TypedInstruction,
    private val idx: TypedInstruction,
) : VariableProvider {
    override fun get(parent: VariableMapping): TypedInstruction {
        return TypedInstruction.ArrayItemAccess(array, idx, forge)
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        error("Cannot be set")
    }

    override val forge: InstanceForge = (array.forge as ArrayInstanceForge).itemForge

    override fun clone(forges: MutableMap<UUID, InstanceForge>): VariableProvider {
        array.forge.clone(forges)
        idx.forge.clone(forges)

        return this
    }
}