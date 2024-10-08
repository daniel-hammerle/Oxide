package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction

interface VariableProvider {
    fun get(parent: VariableMapping): TypedInstruction
    fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction
    fun delete(parent: VariableMapping) {}
    fun physicalName(parent: VariableMapping): String? {
        return null
    }
    fun type(parent: VariableMapping) = get(parent).type
    fun clone(): VariableProvider
    fun genericChangeRequest(parent: VariableMapping, genericName: String, type: Type)
}