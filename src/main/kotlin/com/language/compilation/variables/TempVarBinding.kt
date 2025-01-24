package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.tracking.InstanceForge
import java.util.UUID

class TempVarBinding(private val variable: TempVariable,
                     override var forge: InstanceForge
): VariableProvider {

    override fun get(parent: VariableMapping): TypedInstruction {
         return TypedInstruction.LoadVar(variable.id, forge)
    }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        forge = value.forge
        val id = parent.changeVar(variable.id, value)
        if (id != null) {
            error("Invalid type temp variable cannot change type sizes")
        }
        return TypedInstruction.StoreVar(variable.id, value)
    }

    override fun type(parent: VariableMapping): Type {
        return parent.getType(variable.id)
    }

    override fun clone(forges: MutableMap<UUID, InstanceForge>): VariableProvider {
        return TempVarBinding(variable, forge.clone(forges))
    }

}