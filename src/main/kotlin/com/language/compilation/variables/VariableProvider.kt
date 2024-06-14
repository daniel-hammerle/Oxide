package com.language.compilation.variables

import com.language.compilation.TypedInstruction

interface VariableProvider {
    fun get(parent: ReadOnlyVariableManager?): TypedInstruction
    fun put(value: TypedInstruction, parent: ReadOnlyVariableManager?): TypedInstruction

    fun type(parent: ReadOnlyVariableManager?) = get(parent).type
}