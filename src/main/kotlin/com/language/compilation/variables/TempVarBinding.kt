package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction

class TempVarBinding(private val variable: TempVariable): VariableProvider {

    override fun get(parent: VariableMapping): TypedInstruction {
         return parent.loadVar(variable.id)
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        val (id, ins) = parent.changeVar(variable.id, value)
        if (id != null) {
            error("Invalid type temp variable cannot change type sizes")
        }
        return ins
    }

    override fun clone() = TempVarBinding(variable)

    override fun genericChangeRequest(parent: VariableMapping, genericName: String, type: Type) {
        parent.genericChangeRequest(variable.id, genericName, type)
    }

}