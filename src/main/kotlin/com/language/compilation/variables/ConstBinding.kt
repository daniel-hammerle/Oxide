package com.language.compilation.variables

import com.language.compilation.TypedInstruction

class ConstBinding<T: TypedInstruction.Const>(
    val value: T
) : VariableProvider {
    override fun get(parent: ReadOnlyVariableManager?): T = value

    override fun put(value: TypedInstruction, parent: ReadOnlyVariableManager?): TypedInstruction {
        error("Cannot change const binding")
    }
}