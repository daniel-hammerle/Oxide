// Copyright 2025 Daniel Hammerle
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
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