package com.language.eval

import com.language.codegen.VarFrame
import com.language.compilation.History
import com.language.compilation.IRFunction
import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.metadata.FunctionMetaDataHandle
import com.language.compilation.templatedType.matchesImpl
import com.language.compilation.templatedType.matchesSubset
import com.language.compilation.variables.*
import com.language.lookup.IRLookup

object NoopVariableMapping: VariableMapping {
    override fun new(type: Type): Int {
        TODO("Not yet implemented")
    }

    override fun getType(id: Int): Type {
        TODO("Not yet implemented")
    }

    override fun hasVar(id: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun varCount(): Int {
        TODO("Not yet implemented")
    }

    override fun clone(): VariableMapping {
        TODO("Not yet implemented")
    }

    override fun toVarFrame(): VarFrame {
        TODO("Not yet implemented")
    }

    override fun deleteVar(id: Int) {
        TODO("Not yet implemented")
    }

    override fun minVarCount(count: Int) {
        TODO("Not yet implemented")
    }

    override fun changeVar(id: Int, value: TypedInstruction): Pair<Int?, TypedInstruction> {
        TODO("Not yet implemented")
    }

    override fun loadVar(id: Int): TypedInstruction {
        TODO("Not yet implemented")
    }

    override fun getTempVar(type: Type): TempVariable {
        TODO("Not yet implemented")
    }

    override fun tryAllocateId(id: Int, type: Type): Boolean {
        TODO("Not yet implemented")
    }

    override fun genericChangeRequest(id: Int, genericName: String, type: Type) {
        TODO("Not yet implemented")
    }

}

suspend fun evalFunction(
    function: IRFunction,
    args: List<TypedInstruction.Const>,
    lookup: IRLookup,
    generics: Map<String, Type>,
    history: History
): TypedInstruction.Const {
    if (function.args.size != args.size)
        error("Expected ${function.args.size} but got ${args.size}")
    val variables = VariableManagerImpl(NoopVariableMapping)

    val inferredGenerics = mutableMapOf<String, Type>()

    function.args.zip(args).forEach { (name, instruction) ->
        if (name.second?.matchesSubset(instruction.type, inferredGenerics, function.generics.toMap(), lookup) == false) error("Type mismatch ${instruction.type} : ${name.second}")
        variables.putVar(name.first, ConstBinding(instruction))
    }

    val handle = FunctionMetaDataHandle(generics, function.module, emptyList(), null)

    val result = function.body.inferTypes(variables, lookup, handle, history)
    if (function.returnType?.matchesSubset(result.type, inferredGenerics, function.generics.toMap(), lookup) == false) error("Type mismatch return type ${result.type} : ${function.returnType}")
    if (result !is TypedInstruction.Const) error("Failed")
    return result
}

