package com.language.wasm

import com.language.compilation.IRFunction
import com.language.compilation.IRModule
import com.language.compilation.TypedInstruction

interface Allocator

fun compileModule(module: IRModule) {
    module.functions.forEach { (name, func) ->
        compileFunction(func)
    }
}

fun compileFunction(function: IRFunction) {

}

fun compileInstruction(instruction: TypedInstruction, allocator: Allocator) {
    when(instruction) {
        is TypedInstruction.ArrayForLoop -> TODO()
        is TypedInstruction.ArrayItemAccess -> TODO()
        is TypedInstruction.ArrayLength -> TODO()
        is TypedInstruction.Comparing -> TODO()
        is TypedInstruction.ConstObject -> TODO()
        is TypedInstruction.LoadConstConstArray -> TODO()
        is TypedInstruction.Lambda -> TODO()
        is TypedInstruction.LoadConstBoolean -> TODO()
        is TypedInstruction.LoadConstDouble -> TODO()
        is TypedInstruction.LoadConstInt -> TODO()
        is TypedInstruction.LoadConstString -> TODO()
        is TypedInstruction.ConstructorCall -> TODO()
        is TypedInstruction.DynamicCall -> TODO()
        is TypedInstruction.DynamicPropertyAccess -> TODO()
        is TypedInstruction.DynamicPropertyAssignment -> TODO()
        is TypedInstruction.ForLoop -> TODO()
        is TypedInstruction.If -> TODO()
        is TypedInstruction.Ignore -> TODO()
        is TypedInstruction.InlineBody -> TODO()
        is TypedInstruction.Keep -> TODO()
        is TypedInstruction.LoadArray -> TODO()
        is TypedInstruction.LoadConstArray -> TODO()
        is TypedInstruction.LoadList -> TODO()
        is TypedInstruction.LoadVar -> TODO()
        is TypedInstruction.LogicOperation -> TODO()
        is TypedInstruction.Match -> TODO()
        is TypedInstruction.Math -> TODO()
        is TypedInstruction.ModuleCall -> TODO()
        is TypedInstruction.MultiInstructions -> TODO()
        is TypedInstruction.Noop -> TODO()
        is TypedInstruction.Not -> TODO()
        TypedInstruction.Null -> TODO()
        is TypedInstruction.PlatformSpecificOperation -> TODO()
        is TypedInstruction.Return -> TODO()
        is TypedInstruction.StaticCall -> TODO()
        is TypedInstruction.StaticPropertyAccess -> TODO()
        is TypedInstruction.StoreVar -> TODO()
        is TypedInstruction.Try -> TODO()
        is TypedInstruction.UnrolledForLoop -> TODO()
        is TypedInstruction.While -> TODO()
    }
}