package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction

//Special Variable Provider for tests that allows to simulate variables that are not backed by anything
data class PhantomBinding(var type: Type): VariableProvider {
    override fun get(parent: VariableMapping): TypedInstruction {
        return TypedInstruction.Noop(type)
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        type = value.type
        return TypedInstruction.Noop(Type.Nothing)
    }

    override fun clone(): VariableProvider = PhantomBinding(type)

    override fun genericChangeRequest(parent: VariableMapping, genericName: String, type: Type) {
        val (signature, generics) = (this.type as Type.JvmType).let { it.signature to it.genericTypes }
        this.type = Type.BasicJvmType(signature, generics+ mapOf(genericName to Type.BroadType.Known(type)))
    }
}