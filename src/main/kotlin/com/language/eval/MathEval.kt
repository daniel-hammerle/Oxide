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
package com.language.eval

import com.language.MathOp
import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.isDouble
import com.language.compilation.isNumType

fun evalMath(
    first: TypedInstruction.Const,
    second: TypedInstruction.Const,
    op: MathOp
): TypedInstruction.Const {
    if (op == MathOp.Add && first is TypedInstruction.LoadConstString) {
        return TypedInstruction.LoadConstString(
            first.value + second.value().toString()
        )
    }

    if (!first.type.isNumType() || !second.type.isNumType()) {
        error("Cannot do $first $op $second")
    }

    val num1 = first.toNumericalValue()
    val num2 = second.toNumericalValue()

    val result = when(op) {
        MathOp.Add -> num1 + num2
        MathOp.Sub -> num1 - num2
        MathOp.Mul -> num1 * num2
        MathOp.Div -> num1 / num2
        MathOp.Mod -> num1 % num2
    }

    if (first.type.isDouble() && second.type.isDouble()) {
        return TypedInstruction.LoadConstDouble(result)
    }

    if (result.toInt().toDouble() == result) {
        return TypedInstruction.LoadConstInt(result.toInt())
    }

    return TypedInstruction.LoadConstDouble(result)
}

fun TypedInstruction.Const.value(): Any = when(this) {
    is TypedInstruction.LoadConstConstArray -> this
    is TypedInstruction.Lambda -> this
    is TypedInstruction.LoadConstBoolean -> value
    is TypedInstruction.LoadConstDouble -> value
    is TypedInstruction.LoadConstInt -> value
    is TypedInstruction.LoadConstString -> value
    is TypedInstruction.ConstObject -> TODO()
}

fun TypedInstruction.Const.toNumericalValue() = when(this) {
    is TypedInstruction.LoadConstInt -> value.toDouble()
    is TypedInstruction.LoadConstDouble -> value
    else -> error("$this is not a numerical value however is expected to be numerical")
}