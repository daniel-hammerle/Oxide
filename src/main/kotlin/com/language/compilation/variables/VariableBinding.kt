package com.language.compilation.variables

import com.language.compilation.TypedInstruction

class VariableBinding(val name: String) : VariableProvider {
    override fun get(parent: VariableMapping): TypedInstruction {
        return parent.loadVar(name)
    }

    override fun delete(parent: VariableMapping) {
        parent.deleteVar(name)
    }

    override val physicalName: String
        get() = name

    override fun clone() = VariableBinding(name)

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        return parent.changeVar(name, value)
    }
}