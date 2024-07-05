package com.language.compilation.variables

import com.language.codegen.VarFrame
import com.language.codegen.VarFrameImpl
import com.language.compilation.*
import java.io.Closeable
import java.util.*
import kotlin.math.max


interface VariableMapping {
    fun change(name: String, type: Type): Int
    fun getType(name: String): Type
    fun hasVar(name: String): Boolean
    fun varCount(): Int
    fun getId(name: String): Int
    fun clone(): VariableMapping
    fun merge1(branches: List<VariableMapping>): List<Map<String, Pair<Type, Type>>>
    fun toVarFrame(): VarFrame
    fun deleteVar(name: String)
    fun registerUnchecked(name: String, id: Int)
    fun minVarCount(count: Int)
    fun changeVar(name: String, value: TypedInstruction): TypedInstruction
    fun loadVar(name: String): TypedInstruction
    fun getTempVar(type: Type): TempVariable
    fun tryAllocateId(id: Int, name: String, type: Type): Boolean
    fun genericChangeRequest(name: String, genericName: String, type: Type)
}


class TempVariable(private val variables: VariableMapping, val type: Type) : Closeable {
    private val name: String = "_${UUID.randomUUID()}"
    val id: Int = variables.change(name, type)

    override fun close() {
        variables.deleteVar(name)
    }

}

class VariableMappingImpl private constructor(
    val variables: MutableMap<String, Type> = mutableMapOf(),
    private val variableIds: MutableMap<String, Int> = mutableMapOf(),
    private val variableStack: MutableList<Boolean>     = mutableListOf()
) : VariableMapping {

    private var varMax = 0


    companion object {
        fun fromVariables(variables: Map<String, Type>, reserveThis: Boolean = false) = VariableMappingImpl().apply {
            //add an instance at slot 0
            if (reserveThis)
                insertVariable("__instance__", Type.Object)
            variables.forEach { (name, type) -> insertVariable(name, type) }
        }
    }

    override fun toVarFrame(): VarFrame {
        val variables: List<Type?> = variableStack.mapIndexed { index, b ->
            if (b)
                variables[variableIds.filter { it.value == index }.entries.first().key]
            else
                null
        }
        return VarFrameImpl(variables)
    }

    override fun minVarCount(count: Int) {
        varMax = max(varMax, count)
    }

    override fun change(name: String, type: Type): Int {
        return when {
            //if it already existed and the new type has the same size, we can simply keep the variable slot
            variables[name]?.size == type.size -> {
                variables[name] = type
                variableIds[name]!!
            }
            //if it existed but now has a different size, we have to deallocate the old one and reallocate a new free space
            name in variables -> {
                val oldType = variables[name]!!
                val oldId = variableIds[name]!!
                //clear the old space
                for (i in oldId..<oldId+oldType.size -1) {
                    variableStack[i] = false
                }
                return insertVariable(name, type)
            }
            //if it didn't exist in the first place, we just allocate a variable
            else -> {
                insertVariable(name, type)
            }
        }

    }

    override fun merge1(branches: List<VariableMapping>): List<Map<String, Pair<Type, Type>>> {
        val changes = branches
            .filterIsInstance<VariableMappingImpl>()
            //find the variables that have changed types
            .map { branch ->
                branch.variables.filter { (varName, type) ->
                    varName in variables && variables[varName] != type
                }
            }
            //merge them into one and create unions when necessary
            .reduce { acc, map -> mergeMaps(acc, map) }
        //apply the changes to variables
        for ((name, type) in changes) {
            change(name, type)
        }

        return listOf(emptyMap(), emptyMap())
    }


    private fun mergeMaps(map1: Map<String, Type>, map2: Map<String, Type>): Map<String, Type> {
        val result = mutableMapOf<String, Type>()
        result.putAll(map1)
        for ((key, value) in map2) {
            result.merge(key, value) { existingValue, newValue ->
                existingValue.asBoxed().join(newValue.asBoxed())
            }
        }
        return result
    }


    override fun clone(): VariableMapping {
        return VariableMappingImpl(variables.toMutableMap(), variableIds.toMutableMap(), variableStack.toMutableList())
    }

    private fun insertVariable(name: String, type: Type): Int {
        variables[name] = type
        varMax = max(varMax, variables.size)
        var index = -1

        for (i in 0..variableStack.lastIndex - type.size + 1) {
            val isFree = (0..<type.size).all { !variableStack[i+it] }
            if (isFree) {
                index = i
                break
            }

        }

        when {
            index == -1 -> {
                variableStack.add(true)
                variableIds[name] = variableStack.lastIndex
                return variableStack.lastIndex
            }
            else -> {
                variableStack[index] = true
                variableIds[name] = index
                return index
            }
        }
    }

    override fun deleteVar(name: String) {
        val size = variables.remove(name)!!.size
        val id = variableIds.remove(name)!!
        for (offset in 0..<size) {
            variableStack[id+offset] = false
        }
    }

    override fun registerUnchecked(name: String, id: Int) {
        val oldName = variableIds.entries.first { it.value == id }.key
        variables[oldName]!!.let { variables[name] = it; variableIds[name] = id }
    }

    override fun loadVar(name: String): TypedInstruction {
        return TypedInstruction.LoadVar(getId(name), getType(name))
    }

    override fun changeVar(name: String, value: TypedInstruction): TypedInstruction {
        val id = change(name, value.type)
        return TypedInstruction.StoreVar(id, value)
    }

    override fun getTempVar(type: Type): TempVariable {
        return TempVariable(this, type)
    }

    override fun tryAllocateId(id: Int, name: String, type: Type): Boolean {
        while (variableStack.size <= id) {
            variableStack.add(false)
        }
        if (variableStack[id]) return false
        varMax = max(varMax, id+1)
        variables[name] = type
        variableIds[name] = id
        return true
    }

    override fun genericChangeRequest(name: String, genericName: String, type: Type) {
        val (signature, generics) = (getType(name) as Type.JvmType).let { it.signature to it.genericTypes }
        change(name, Type.BasicJvmType(signature, generics + mapOf(genericName to Type.BroadType.Known(type))))
    }

    override fun getType(name: String): Type {
        return variables[name] ?: error("No var with name $name")
    }

    override fun hasVar(name: String): Boolean {
        return name in variables
    }

    override fun getId(name: String): Int {
        return variableIds[name] ?: error("No variable with name `$name` ($variableIds)")
    }

    override fun varCount(): Int = varMax

}

