package com.language.compilation

object IteratorI : ImplicitInterface {
    override val functions: Map<String, Pair<List<Type>, suspend (Type, IRLookup) -> Boolean>> = mapOf(
        "hasNext" to (emptyList<Type>() to { it, _ -> it is Type.BoolT }) ,
        "next" to (emptyList<Type>() to { type, _ -> type != Type.Nothing })
    )
}

object IterableI : ImplicitInterface {
    override val functions: Map<String, Pair<List<Type>, suspend (Type, IRLookup) -> Boolean>> = mapOf(
        "iterator" to (emptyList<Type>() to { type, lookUp -> IteratorI.validate(lookUp, type) })
    )
}
