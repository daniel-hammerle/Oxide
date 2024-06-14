package com.language.compilation.variables

import com.language.codegen.VarFrame
import com.language.compilation.Type
import com.language.compilation.TypedInstruction

class VariableManagerImpl(
    val parent: ReadOnlyVariableManager? = null,
    private val variables: MutableMap<String, VariableProvider> = mutableMapOf()
) : VariableManager {



    override fun loadVar(name: String): TypedInstruction {
        val provider = variables[name]
        if (provider != null)
            return provider.get(parent)

        return parent?.loadVar(name) ?: error("No variable with name `$name` found.")
    }


    override fun changeVar(name: String, value: TypedInstruction): TypedInstruction {
        if (name in variables) {
            return variables[name]!!.put(value, parent)
        }

        parent?.changeVar(name, value)?.let { return it }

        error("")
    }

    override fun change(name: String, type: Type): Int {
        return parent?.change(name, type)!!
    }

    override fun toVarFrame(): VarFrame = parent!!.toVarFrame()

    override fun getType(name: String): Type = variables[name]?.type(parent) ?: parent!!.getType(name)
    override fun tryGetMapping(): VariableMapping? = parent?.tryGetMapping()


    override fun merge(branches: List<VariableManager>): List<Map<String, Pair<Type, Type>>> = parent!!.merge(branches)

    override fun getTempVar(type: Type): TempVariable = parent!!.getTempVar(type)

    override fun clone(): VariableManagerImpl {
        return VariableManagerImpl(parent?.clone(), variables)
    }

    override fun putVar(name: String, provider: VariableProvider) {
        variables[name] = provider
    }

}