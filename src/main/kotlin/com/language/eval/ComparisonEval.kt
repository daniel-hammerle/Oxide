package com.language.eval

import com.language.CompareOp
import com.language.compilation.Instruction
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