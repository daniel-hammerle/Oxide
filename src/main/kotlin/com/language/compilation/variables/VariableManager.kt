package com.language.compilation.variables

import com.language.codegen.VarFrame
import com.language.compilation.Type
import com.language.compilation.TypedInstruction

interface VariableManager : ReadOnlyVariableManager {
    fun putVar(name: String, provider: VariableProvider)

    override fun clone(): VariableManager
}

interface ReadOnlyVariableManager {
    fun loadVar(name: String): TypedInstruction

    fun changeVar(name: String, value: TypedInstruction): TypedInstruction

    fun change(name: String, type: Type): Int

    fun toVarFrame(): VarFrame

    fun getType(name: String): Type

    fun tryGetMapping(): VariableMapping?

    fun merge(branches: List<VariableManager>): List<Map<String, Pair<Type, Type>>>

    fun getTempVar(type: Type): TempVariable

    fun clone(): ReadOnlyVariableManager
}