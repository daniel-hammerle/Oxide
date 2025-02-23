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

import com.language.CompareOp
import com.language.compilation.TypedInstruction

fun evalComparison(
    first: TypedInstruction.Const,
    second: TypedInstruction.Const,
    op: CompareOp
): TypedInstruction.LoadConstBoolean {
    val boolean = when(op) {
        CompareOp.Eq -> first.value() == second.value()
        CompareOp.Neq -> first.value() != second.value()
        CompareOp.Gt -> first.toNumericalValue() > second.toNumericalValue()
        CompareOp.St -> first.toNumericalValue() < second.toNumericalValue()
        CompareOp.EGt -> first.toNumericalValue() >= second.toNumericalValue()
        CompareOp.ESt -> first.toNumericalValue() <= second.toNumericalValue()
    }

    return TypedInstruction.LoadConstBoolean(boolean)
}

