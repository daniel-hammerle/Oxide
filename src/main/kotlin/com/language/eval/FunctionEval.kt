package com.language.eval

import com.language.compilation.IRFunction
import com.language.compilation.ModuleLookup
import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.metadata.FunctionMetaDataHandle
import com.language.compilation.metadata.MetaDataHandle
import com.language.compilation.variables.ConstBinding
import com.language.compilation.variables.VariableManagerImpl
import com.language.lookup.IRLookup

suspend fun evalFunction(
    function: IRFunction,
    args: List<TypedInstruction.Const>,
    lookup: IRLookup,
    generics: Map<String, Type>
): TypedInstruction.Const {
    if (function.args.size != args.size)
        error("Expected ${function.args.size} but got ${args.size}")
    val variables = VariableManagerImpl()

    function.args.zip(args).forEach { (name, instruction) ->
        variables.putVar(name, ConstBinding(instruction))
    }

    val handle = FunctionMetaDataHandle(generics, function.module, emptyList())

    val result = function.body.inferTypes(variables, lookup, handle)
    if (result !is TypedInstruction.Const) error("Failed")
    return result
}