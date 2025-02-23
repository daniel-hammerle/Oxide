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

import com.language.BooleanOp
import com.language.compilation.Type
import com.language.compilation.TypedInstruction

fun partialEvalBoolLogic(const: TypedInstruction.Const, other: TypedInstruction, op: BooleanOp): TypedInstruction {
    val value = const.value() as Boolean
    return when {
        op == BooleanOp.And && !value->  TypedInstruction.LoadConstBoolean(false)
        op == BooleanOp.Or && value -> TypedInstruction.LoadConstBoolean(true)
        else -> other
    }
}

fun evalBoolLogic(first: TypedInstruction.Const, second: TypedInstruction.Const, op: BooleanOp): TypedInstruction.Const {
    val firstVal = first.value() as Boolean
    val secondVal = second.value() as Boolean
    val result = when(op) {
        BooleanOp.And -> firstVal && secondVal
        BooleanOp.Or -> firstVal || secondVal
    }
    return TypedInstruction.LoadConstBoolean(result)
}

fun evalLogicNot(ins: TypedInstruction.Const): TypedInstruction.Const {
    val insVal = ins.value() as Boolean
    return TypedInstruction.LoadConstBoolean(!insVal)
}