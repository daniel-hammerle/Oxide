package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction

class ConstBinding<T: TypedInstruction.Const>(
    val value: T
) : VariableProvider {
    override fun get(parent: VariableMapping): T = value

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        error("Cannot change const binding")
    }

    override fun clone(): VariableProvider = this //this is fine since its const
    override fun genericChangeRequest(parent: VariableMapping, genericName: String, type: Type) {
        error("Generics of constants cannot change")
    }
}