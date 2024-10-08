package com.language.eval

import com.language.codegen.VarFrame
import com.language.compilation.IRFunction
import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.metadata.FunctionMetaDataHandle
import com.language.compilation.variables.*
import com.language.lookup.IRLookup

object NoopVariableMapping: VariableMapping {
    override fun getName(id: Int): String {
        error("Not supported")
    }

    override fun change(name: String, type: Type): Int {
        error("Not supported")
    }

    override fun getType(name: String): Type {
        error("Not supported")
    }

    override fun hasVar(name: String): Boolean {
        error("Not supported")
    }

    override fun varCount(): Int {
        error("Not supported")
    }

    override fun getId(name: String): Int {
        error("Not supported")
    }

    override fun clone(): VariableMapping = this

    override fun merge1(branches: List<VariableMapping>): List<Map<String, Pair<Type, Type>>> {
        error("Not supported")
    }

    override fun toVarFrame(): VarFrame {
        error("Not supported")
    }

    override fun deleteVar(name: String) {
        error("Not supported")
    }

    override fun registerUnchecked(name: String, id: Int) {
        error("Not supported")
    }

    override fun minVarCount(count: Int) {
        error("Not supported")
    }

    override fun changeVar(name: String, value: TypedInstruction): TypedInstruction {
        error("Not supported")
    }

    override fun loadVar(name: String): TypedInstruction {
        error("Not supported")
    }

    override fun getTempVar(type: Type): TempVariable {
        error("Not supported")
    }

    override fun tryAllocateId(id: Int, name: String, type: Type): Boolean {
        error("Not supported")
    }

    override fun genericChangeRequest(name: String, genericName: String, type: Type) {
    }

}

suspend fun evalFunction(
    function: IRFunction,
    args: List<TypedInstruction.Const>,
    lookup: IRLookup,
    generics: Map<String, Type>
): TypedInstruction.Const {
    if (function.args.size != args.size)
        error("Expected ${function.args.size} but got ${args.size}")
    val variables = VariableManagerImpl(NoopVariableMapping)

    function.args.zip(args).forEach { (name, instruction) ->
        variables.putVar(name, ConstBinding(instruction))
    }

    val handle = FunctionMetaDataHandle(generics, function.module, emptyList())

    val result = function.body.inferTypes(variables, lookup, handle)
    if (result !is TypedInstruction.Const) error("Failed")
    return result
}

