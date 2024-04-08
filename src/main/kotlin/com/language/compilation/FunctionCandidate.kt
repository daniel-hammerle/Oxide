package com.language.compilation

import com.language.codegen.generateJVMFunctionSignature

data class FunctionCandidate(val oxideArgs: List<Type>, val jvmArgs: List<Type>, val jvmReturnType: Type, val oxideReturnType: Type)

fun FunctionCandidate.toJvmDescriptor() = generateJVMFunctionSignature(jvmArgs, jvmReturnType)

val FunctionCandidate.hasGenericReturnType: Boolean
    get() = jvmReturnType != oxideReturnType