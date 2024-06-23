package com.language.compilation.variables

import com.language.compilation.TypedInstruction

interface VariableProvider {
    fun get(parent: VariableMapping): TypedInstruction
    fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction
    fun delete(parent: VariableMapping) {}
    val physicalName: String?
        get() = null
    fun type(parent: VariableMapping) = get(parent).type
    fun clone(): VariableProvider
}