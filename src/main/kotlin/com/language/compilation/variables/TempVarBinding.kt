package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction

class TempVarBinding(private val variable: TempVariable): VariableProvider {
    private lateinit var name: String

    override fun get(parent: VariableMapping): TypedInstruction {
        if (!this::name.isInitialized) {
            name= parent.getName(variable.id)
        }
         return parent.loadVar(name)
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        if (!this::name.isInitialized) {
            name= parent.getName(variable.id)
        }
        return parent.changeVar(name, value)
    }

    override fun physicalName(parent: VariableMapping): String {
        return parent.getName(variable.id)
    }

    override fun clone() = TempVarBinding(variable)

    override fun genericChangeRequest(parent: VariableMapping, genericName: String, type: Type) {
        if (!this::name.isInitialized) {
            name= parent.getName(variable.id)
        }
        parent.genericChangeRequest(name, genericName, type)
    }

}