package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction

class VariableBinding(var id: Int) : VariableProvider {

    companion object {
        fun forValue(value: TypedInstruction, parent: VariableMapping): Pair<TypedInstruction, VariableBinding> {
            val id = parent.new(value.type)
            return TypedInstruction.StoreVar(id, value) to VariableBinding(id)
        }

        fun ofType(type: Type, parent: VariableMapping): VariableBinding {
            val id = parent.new(type)
            return VariableBinding(id)
        }
    }

    override fun get(parent: VariableMapping): TypedInstruction {
        return parent.loadVar(id)
    }

    override fun delete(parent: VariableMapping) {
        parent.deleteVar(id)
    }

    override val physicalId: Int = id


    override fun clone() = VariableBinding(id)
    override fun genericChangeRequest(parent: VariableMapping, genericName: String, type: Type) {
        parent.genericChangeRequest(id, genericName, type)
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        val(newId, ins) = parent.changeVar(id, value)
        if (newId != null) {
            id = newId
        }
        return ins
    }
}