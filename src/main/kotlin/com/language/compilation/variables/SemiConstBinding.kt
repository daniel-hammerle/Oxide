package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.generateName

class SemiConstBinding(
    var value: TypedInstruction.Const
) : VariableProvider, AllocationRecord {
    @Volatile
    private var id: Int? = null


    override fun clone(): VariableProvider {
        return SemiConstBinding(value).also { it.value = this.value }
    }

    override fun genericChangeRequest(parent: VariableMapping, genericName: String, type: Type) {
        synchronized(this) {
            when(val n = id) {
                is Int -> parent.genericChangeRequest(n, genericName, type)
                else -> error("Cannot change generic type of const")
            }
        }
    }

    override fun get(parent: VariableMapping): TypedInstruction {
        return synchronized(this) {
            when(val id = id) {
                is Int -> parent.loadVar(id)
                else -> value
            }
        }
    }


    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        if (allocated && value is TypedInstruction.Const) {
            synchronized(this) {
                //if we had a physical allocation, we can now remove it since we now have a constant value
                if (id != null) {
                    parent.deleteVar(id!!)
                    id = null
                }
                this.value = value
            }
            return TypedInstruction.Noop(Type.Nothing)
        }
        val ins = synchronized(this) {
            when(val i = id) {
                is Int -> {
                    val (id, ins) = parent.changeVar(i, value)
                    if (id != null) {
                        this.id = id
                    }
                    ins
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
        synchronized(this) {
            when(val id = id) {
                is Int -> parent.deleteVar(id)
                else -> {}
            }
        }
    }

    override val physicalId: Int? = synchronized(this) { id }

    override val allocated: Boolean
        get() = id != null
}