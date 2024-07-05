package com.language.compilation.variables

import com.language.codegen.VarFrame
import com.language.compilation.Instruction
import com.language.compilation.ScopeAdjustment
import com.language.compilation.Type
import com.language.compilation.TypedInstruction

interface VariableManager : ReadOnlyVariableManager {
    fun putVar(name: String, provider: VariableProvider)

    override fun clone(): VariableManager
}

interface ReadOnlyVariableManager {
    val parent: VariableMapping

    fun loadVar(name: String): TypedInstruction

    fun changeVar(name: String, value: TypedInstruction): TypedInstruction

    fun change(name: String, type: Type): Int

    fun toVarFrame(): VarFrame

    fun getType(name: String): Type

    fun tryGetMapping(): VariableMapping?

    fun genericChangeRequest(name: String, genericName: String, type: Type)

    val variables: Map<String, VariableProvider>

    fun merge(branches: List<VariableManager>): List<ScopeAdjustment>

    /**
     * Produces the scope adjustment that needs to be performed before a scope is repeatedly executed
     * @param postLoop The variables after type-checking the scope
     * @param parentScope The parent scope where in addition to modifying **this** the changes are performed
     * ## Usage
     * Infer the types of the scope once on a clone of **this** which is then the [postLoop]
     * The scope adjustment produced will need to be executed before the first loop iteration.
     * Infer the types of the scope once again with **this** as the variables and the result will be the proper function body
     */
    fun loopMerge(postLoop: VariableManager, parentScope: VariableManager?): ScopeAdjustment

    fun getTempVar(type: Type): TempVariable

    fun clone(): ReadOnlyVariableManager

    fun deleteVar(name: String)

    fun hasVar(name: String): Boolean
}