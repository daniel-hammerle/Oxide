package com.language.lookup.jvm.contract

import com.language.compilation.Type
import com.language.compilation.TypedInstruction

/**
 * @return A [ContractItem] if the pattern matched, indicating a special case, or null if it didn't match
 *
 */
fun ContractVariant.matches(args: List<Type>): ContractItem? {
    if (arguments.size != args.size) return null

    val condition = arguments
        .zip(args)
        .all { (pattern, ins) -> pattern.matches(ins) }

    return if (condition) {
        returnType
    } else {
        null
    }
}

fun ContractItem.matches(type: Type): Boolean = when(this) {
    ContractItem.True -> type == Type.BoolTrue
    ContractItem.False -> type == Type.BoolFalse
    ContractItem.Null -> type == Type.Null
    ContractItem.NotNull -> type.isNotNull()
    ContractItem.Ignore -> true
    ContractItem.Fail -> type == Type.Never
}

fun Type.isNotNull() = this != Type.Null && if (this is Type.Union) this.entries.none { it == Type.Null } else true