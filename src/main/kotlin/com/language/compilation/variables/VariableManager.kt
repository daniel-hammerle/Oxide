package com.language.compilation.variables

import com.language.codegen.VarFrame
import com.language.compilation.ScopeAdjustment
import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.join
import com.language.compilation.tracking.BroadForge
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.join

interface TypeVariableManager {
    fun get(name: String): BroadForge
    fun set(name: String, tp: BroadForge)

    fun branch(): TypeVariableManager
    fun merge(branches: Iterable<TypeVariableManager>)

    val vars: Map<String, BroadForge>
}

class TypeVariableManagerImpl(override var vars: MutableMap<String, BroadForge> = mutableMapOf()) : TypeVariableManager {
    override fun get(name: String) = vars[name] ?: error("No variable $name")

    override fun set(name: String, tp: BroadForge) {
        vars[name] = tp
    }

    override fun branch(): TypeVariableManager = TypeVariableManagerImpl(vars.toMutableMap())

    override fun merge(branches: Iterable<TypeVariableManager>) {
        vars = branches.asSequence()
            .map { it.vars }
            .reduce { acc, map -> acc.mergeConflicts(map) { a, b -> a.join(b) } }
            .toMutableMap()
    }

}

inline fun <K, A> Map<K, A>.mergeConflicts(
    other: Map<K, A>,
    closure: (A, A) -> A
): Map<K, A> {
    val result = this.toMutableMap()

    for ((key, value) in other) {
        result[key] = result[key]?.let { existingValue ->
            closure(existingValue, value)
        } ?: value
    }

    return result
}

interface VariableManager : ReadOnlyVariableManager {
    fun putVar(name: String, provider: VariableProvider)

    fun getExternal(provider: VariableProvider): TypedInstruction {
        return provider.get(parent)
    }

    fun reference(newName: String, oldName: String)

    fun change(name: String, forge: InstanceForge): Int

    override fun clone(): VariableManager
}

interface ReadOnlyVariableManager {
    val parent: VariableMapping

    fun loadVar(name: String): TypedInstruction

    fun changeVar(name: String, value: TypedInstruction): TypedInstruction


    fun toVarFrame(): VarFrame

    fun getType(name: String): Type

    fun getForge(name: String): InstanceForge

    fun mapping(): VariableMapping

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