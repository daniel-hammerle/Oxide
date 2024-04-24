package com.language

import com.language.compilation.SignatureString
import com.language.compilation.Type

sealed interface Expression {
    sealed interface Const : Expression

    data class ConstNum(val num: Double) : Const
    data class ConstStr(val str: String) : Const
    data class ConstBool(val bool: Boolean) : Const

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
    data class Match(val matchable: Expression, val branches: List<Pair<Pattern, Expression>>) : Expression
}

sealed interface Pattern {

    val bindingNames: Set<String>

    data class Const(val value: Expression.Const) : Pattern {
        override val bindingNames: Set<String> = emptySet()
    }
    data class Binding(val name: String) : Pattern {
        override val bindingNames: Set<String> = setOf(name)
    }
    data class Destructuring(val type: Type, val patterns: List<Pattern>) : Pattern {
        override val bindingNames: Set<String> = patterns.flatMap { it.bindingNames }.toSet()
    }
    data class Conditional(val condition: Expression, val parent: Pattern) : Pattern {
        override val bindingNames: Set<String> = parent.bindingNames
    }
}

sealed interface Statement {
    data class Expr(val expression: Expression) : Statement
    data class While(val condition: Expression, val body: Expression): Statement
    data class Assign(val name: String, val value: Expression) : Statement
}

data class Module(val children: Map<String, ModuleChild>)

sealed interface ModuleChild

data class Function(val args: List<String>, val body: Expression) : ModuleChild

data class Struct(val args: Map<String, String>) : ModuleChild

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