package com.language

sealed interface Expression {
    data class ConstNum(val num: Double) : Expression
    data class ConstStr(val str: String) : Expression
    data class ConstBool(val bool: Boolean) : Expression

    data class Invoke(val parent: Expression, val args: Map<String, Expression>): Expression

    data class AccessProperty(val parent: Expression, override val name: String): Invokable

    sealed interface Invokable : Expression {
        val name: String
    }
    data class UnknownSymbol(override val name: String): Invokable

    data class VariableSymbol(override val name: String): Invokable


    data class Math(val first: Expression, val second: Expression, val op: MathOp) : Expression
    data class Comparing(val first: Expression, val second: Expression, val op: CompareOp) : Expression
    data class ReturningScope(val expressions: List<Statement>) : Expression
    data class IfElse(val condition: Expression, val body: Expression, val elseBody: Expression?) : Expression
}

sealed interface Statement {
    data class Expr(val expression: Expression) : Statement
    data class While(val condition: Expression, val body: Expression): Statement
    data class Assign(val name: String, val value: Expression) : Statement
}

data class Module(val children: Map<String, ModuleChild>)

sealed interface ModuleChild

data class Function(val args: List<String>, val body: Expression) : ModuleChild

data class Variable(val initialValue: Expression) : ModuleChild


enum class MathOp {
    Add,
    Sub,
    Mul,
    Div
}

enum class CompareOp {
    Eq,
    Neq,
    Gt,
    St,
    EGt,
    ESt
}