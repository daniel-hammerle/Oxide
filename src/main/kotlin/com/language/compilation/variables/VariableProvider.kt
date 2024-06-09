package com.language.compilation.variables

import com.language.compilation.TypedInstruction

interface VariableProvider {
    fun get(): TypedInstruction
    fun put(value: TypedInstruction): TypedInstruction

    fun type() = get().type
}