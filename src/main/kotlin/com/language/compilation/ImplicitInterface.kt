package com.language.compilation

import com.language.lookup.IRLookup

interface ImplicitInterface {
    val functions: Map<String, Pair<List<Type>, suspend (type: Type, lookUp: IRLookup, hist: History) -> Boolean>>

    suspend fun validate(lookup: IRLookup, type: Type, history: History): Boolean = functions.all { (name, signature) ->
        val (args, returnType) = signature
        val result = runCatching { lookup.lookUpCandidate(type, name, args, history) }.getOrElse {
            return false
        }
        returnType(result.oxideReturnType, lookup, history)
    }
}