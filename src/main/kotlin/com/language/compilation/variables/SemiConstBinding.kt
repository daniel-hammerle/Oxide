package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.generateName
import com.language.compilation.tracking.InstanceForge
import java.util.UUID

class SemiConstBinding(
    var value: TypedInstruction.Const,
    override var forge: InstanceForge
) : VariableProvider, AllocationRecord {
    @Volatile
    private var id: Int? = null

    override fun get(parent: VariableMapping): TypedInstruction {
        return synchronized(this) {
            when(val id = id) {
                is Int -> TypedInstruction.LoadVar(id, forge)
                else -> value
            }
        }
    }


    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        this.forge = value.forge
        if (allocated && value is TypedInstruction.Const) {
            //if we had a physical allocation, we can now remove it since we now have a constant value
            if (id != null) {
                parent.deleteVar(id!!)
                id = null
            }
            this.value = value
            return TypedInstruction.Noop(Type.Nothing)
        }
        val ins = synchronized(this) {
            when(val i = id) {
                is Int -> {
                    val id = parent.changeVar(i, value)
                    if (id != null) {
                        this.id = id
                    }
                    return TypedInstruction.StoreVar(this.id!!, value)
                }
                else -> {
                    this.id = parent.new(value.type)
                    return TypedInstruction.StoreVar(id!!, value)
                }
            }
        }
        return ins
    }

    override fun delete(parent: VariableMapping) {
        when(val id = id) {
            is Int -> parent.deleteVar(id)
            else -> {}
        }
    }

    override val physicalId: Int? = id
    override fun clone(forges: MutableMap<UUID, InstanceForge>): VariableProvider {
        return SemiConstBinding(value, forge.clone(forges)).also { it.value = this.value }
    }

    override val allocated: Boolean
        get() = id != null
}