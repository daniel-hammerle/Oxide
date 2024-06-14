package com.language.compilation.variables

import com.language.compilation.TypedInstruction

class ConstBinding<T: TypedInstruction>(
    val value: T
) : VariableProvider {
    override fun get(): T = value

    override fun put(value: TypedInstruction): TypedInstruction {
        error("Cannot change const binding")
    }
}