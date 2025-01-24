package com.language.compilation.variables

import com.language.compilation.TypedInstruction
import com.language.compilation.tracking.InstanceForge
import java.util.UUID

class VariableBinding(var id: Int, override var forge: InstanceForge) : VariableProvider {

    companion object {
        fun forValue(value: TypedInstruction, parent: VariableMapping): Pair<TypedInstruction, VariableBinding> {
            val id = parent.new(value.type)
            return TypedInstruction.StoreVar(id, value) to VariableBinding(id, value.forge)
        }

        fun ofType(forge: InstanceForge, parent: VariableMapping): VariableBinding {
            val id = parent.new(forge.type)
            return VariableBinding(id, forge)
        }
    }

    override fun get(parent: VariableMapping): TypedInstruction {
        return TypedInstruction.LoadVar(id, forge)
    }

    override fun delete(parent: VariableMapping) {
        parent.deleteVar(id)
    }

    override val physicalId: Int = id


    override fun clone(forges: MutableMap<UUID, InstanceForge>) = VariableBinding(id, forge.clone(forges)) //TODO

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        this.forge = value.forge
        val newId = parent.changeVar(id, value)
        if (newId != null) {
            id = newId
        }
        return TypedInstruction.StoreVar(id, value)
    }
}