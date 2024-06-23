package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.generateName

class SemiConstBinding(
    var value: TypedInstruction.Const
) : VariableProvider, AllocationRecord {
    @Volatile
    private var name: String? = null

    override val physicalName: String?
        get() = synchronized(this) { name }

    override fun clone(): VariableProvider {
        return SemiConstBinding(value).also { it.name = this.name }
    }

    override fun get(parent: VariableMapping): TypedInstruction {
        return synchronized(this) {
            when(name) {
                is String -> parent.loadVar(name!!)
                else -> value
            }
        }
    }

    private fun isStillConst() = synchronized(this) { name == null }

    override fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction {
        if (isStillConst() && value is TypedInstruction.Const) {
            synchronized(this) { this.value = value }
            return TypedInstruction.Noop(Type.Nothing)
        }
        val name = synchronized(this) {
            when(name) {
                is String -> name!!
                else -> "__semiConst__${generateName()}".also { name = it }
            }
        }
        return parent.changeVar(name, value)
    }

    override fun delete(parent: VariableMapping) {
        synchronized(this) {
            when(val n = name) {
                is String -> parent.deleteVar(n)
                else -> {}
            }
        }
    }

    override val allocated: Boolean
        get() = name != null
}