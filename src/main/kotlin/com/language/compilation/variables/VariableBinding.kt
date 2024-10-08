package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction

class VariableBinding(val name: String) : VariableProvider {
    override fun get(parent: VariableMapping): TypedInstruction {
        return parent.loadVar(name)
    }

    override fun delete(parent: VariableMapping) {
        parent.deleteVar(name)
    }

    override fun physicalName(parent: VariableMapping): String? {
        return name
    }

    override fun clone() = VariableBinding(name)
    override fun genericChangeRequest(parent: VariableMapping, genericName: String, type: Type) {
        parent.genericChangeRequest(name, genericName, type)
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        return parent.changeVar(name, value)
    }
}