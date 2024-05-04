package com.language.compilation

interface ImplicitInterface {
    val functions: Map<String, Pair<List<Type>, suspend (type: Type, lookUp: IRModuleLookup) -> Boolean>>

    suspend fun validate(lookup: IRModuleLookup, type: Type): Boolean = functions.all { (name, signature) ->
        val (args, returnType) = signature
        val result = runCatching { lookup.lookUpCandidate(type, name, args) }.getOrNull() ?: return false
        returnType(result.oxideReturnType, lookup)
    }
}