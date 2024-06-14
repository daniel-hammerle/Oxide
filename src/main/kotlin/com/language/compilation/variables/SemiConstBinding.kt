package com.language.compilation.variables

import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.generateName

class SemiConstBinding(
    var value: TypedInstruction.Const
) : VariableProvider {
    private var name: String? = null

    override fun get(parent: ReadOnlyVariableManager?): TypedInstruction {
        return synchronized(this) {
            when(name) {
                is String -> parent!!.loadVar(name!!)
                else -> value
            }
        }
    }

    private fun isStillConst() = synchronized(this) { name == null }

    override fun put(value: TypedInstruction, parent: ReadOnlyVariableManager?): TypedInstruction {
        if (isStillConst() && value is TypedInstruction.Const) {
            synchronized(this) { this.value = value }
            return TypedInstruction.Noop(Type.Nothing)
        }
        val name = synchronized(this) {
            when(name) {
                is String -> name!!
                else -> generateName().also { name = it }
            }
        }
        return parent!!.changeVar(name, value)
    }
}