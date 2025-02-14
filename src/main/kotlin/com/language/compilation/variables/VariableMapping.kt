package com.language.compilation.variables

import com.language.codegen.VarFrame
import com.language.codegen.VarFrameImpl
import com.language.compilation.*
import java.io.Closeable
import kotlin.math.max


interface VariableMapping {
    fun new(type: Type): Int
    fun getType(id: Int): Type
    fun hasVar(id: Int): Boolean
    fun varCount(): Int
    fun clone(): VariableMapping
    fun toVarFrame(): VarFrame
    fun deleteVar(id: Int)
    fun minVarCount(count: Int)
    fun changeVar(id: Int, value: TypedInstruction): Int?
    fun getTempVar(type: Type): TempVariable
    fun tryAllocateId(id: Int, type: Type): Boolean
    fun genericChangeRequest(id: Int, genericName: String, type: Type)
}


class TempVariable(private val variables: VariableMapping, val type: Type) : Closeable {
    val id: Int = variables.new(type)

    override fun close() {
        variables.deleteVar(id)
    }

}

class VariableMappingImpl private constructor(
    val variables: MutableMap<Int, Type> = mutableMapOf(),
    private val variableStack: MutableList<Boolean>     = mutableListOf()
) : VariableMapping {

    private var varMax = 0


    companion object {
        fun empty(): VariableMapping {
            return VariableMappingImpl()
        }
    }

    override fun toVarFrame(): VarFrame {
        val variables: List<Type?> = variableStack.mapIndexed { index, b ->
            if (b)
                variables[index]
            else
                null
        }
        return VarFrameImpl(variables)
    }

    override fun minVarCount(count: Int) {
        varMax = max(varMax, count)
    }

    override fun new(type: Type): Int = insertVariable(type)


    override fun clone(): VariableMapping {
        return VariableMappingImpl(variables.toMutableMap(), variableStack.toMutableList())
    }

    private fun insertVariable(type: Type): Int {
        varMax = max(varMax, variables.size + 1)
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
                variables[variableStack.lastIndex] = type
                return variableStack.lastIndex
            }
            else -> {
                variableStack[index] = true
                variables[index] = type
                return index
            }
        }
    }

    override fun deleteVar(id: Int) {
        val size = variables.remove(id)!!.size
        for (offset in 0..<size) {
            variableStack[id+offset] = false
        }
    }

    private fun reallocate(id: Int, newType: Type): Int {
        val oldType = variables[id] ?: return new(newType)

        when {
            oldType.size == newType.size -> {
                variables[id] = newType
                return id
            }
            oldType.size > newType.size -> {
                //if we need less space, we can simply remove it from the used section
                for (i in newType.size..<oldType.size) {
                    variableStack[id+i] = false
                }
                return id
            }
            oldType.size < newType.size -> {
                deleteVar(id)
                return new(newType)
            }
        }
        error("Unreachable")
    }

    override fun changeVar(id: Int, value: TypedInstruction): Int? {
        if (id !in variables) {
            error("Invalid argument")
        }
        if (value.type != variables[id]) {
            val newId = reallocate(id, value.type)
            return newId
        }
        return null
    }

    override fun getTempVar(type: Type): TempVariable {
        return TempVariable(this, type)
    }

    override fun tryAllocateId(id: Int, type: Type): Boolean {
        while (variableStack.size <= id) {
            variableStack.add(false)
        }
        if (variableStack[id]) return false
        varMax = max(varMax, id+1)
        variables[id] = type
        variableStack[id] = true
        return true
    }

    override fun genericChangeRequest(id: Int, genericName: String, type: Type) {
        val (signature, generics) = (getType(id) as Type.JvmType).let { it.signature to it.genericTypes }
        variables[id] = Type.BasicJvmType(signature, generics + mapOf(genericName to type))
    }

    override fun getType(id: Int): Type {
        return variables[id] ?: error("No var with id $id")
    }

    override fun hasVar(id: Int): Boolean {
        return id in variables
    }

    override fun varCount(): Int = varMax

}

