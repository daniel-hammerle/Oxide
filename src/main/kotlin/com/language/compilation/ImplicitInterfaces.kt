package com.language.compilation

import com.language.lookup.IRLookup

object IteratorI : ImplicitInterface {
    override val functions: Map<String, Pair<List<Type>, suspend (Type, IRLookup, History) -> Boolean>> = mapOf(
        "hasNext" to (emptyList<Type>() to { it, _, _ ->
            it is Type.BoolT }) ,
        "next" to (emptyList<Type>() to { type, _, _ ->
            type != Type.Nothing })
    )
}

object IterableI : ImplicitInterface {
    override val functions: Map<String, Pair<List<Type>, suspend (Type, IRLookup, History) -> Boolean>> = mapOf(
        "iterator" to (emptyList<Type>() to { type, lookUp, hist ->
            IteratorI.validate(lookUp, type, hist) }
        )
    )
}
