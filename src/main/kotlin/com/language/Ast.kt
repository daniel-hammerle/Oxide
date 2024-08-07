package com.language

import com.language.compilation.GenericType
import com.language.compilation.SignatureString
import com.language.compilation.modifiers.Modifiers

sealed interface Expression {

    sealed interface Const : Expression
    data object ConstNull : Const

    data class ConstNum(val num: Double) : Const
    data class ConstStr(val str: String) : Const
    data class ConstBool(val bool: Boolean) : Const

    sealed interface Array : Expression {
        val arrayType: ArrayType
    }

    data class ConstArray(override val arrayType: ArrayType, val items: List<ConstructingArgument>): Array
    data class DefaultArray(override val arrayType: ArrayType, val defaultItem: Expression, val size: Expression) : Array
    data class CollectorArray(override val arrayType: ArrayType, val name: String, val parent: Expression, val body: ConstructingArgument): Array

    data class Invoke(val parent: Expression, val args: Map<String, Expression>): Expression

    data class AccessProperty(val parent: Expression, override val name: String): Invokable

    sealed interface Invokable : Expression {
        val name: String
    }
    data class UnknownSymbol(val sigName: String): Invokable {
        override val name: String = sigName
    }

    data class VariableSymbol(override val name: String): Invokable

    data class Try(val expression: Expression): Expression

    data class Math(val first: Expression, val second: Expression, val op: MathOp) : Expression
    data class Comparing(val first: Expression, val second: Expression, val op: CompareOp) : Expression
    data class ReturningScope(val expressions: List<Statement>) : Expression
    data class Lambda(val args: List<String>, val body: Expression, val capturedVariables: Set<String>): Expression
    data class IfElse(val condition: Expression, val body: Expression, val elseBody: Expression?) : Expression
    data class Match(val matchable: Expression, val branches: List<Pair<Pattern, Expression>>) : Expression
    data class Keep(val value: Expression): Expression
}

sealed interface ConstructingArgument {
    data class Collect(val expression: Expression) : ConstructingArgument
    data class Normal(val expression: Expression) : ConstructingArgument
    data class ForLoop(val forLoopConstruct: ForLoopConstruct): ConstructingArgument
}

sealed interface TemplatedType {
    data object IntT: TemplatedType
    data object DoubleT : TemplatedType
    data object BoolT: TemplatedType
    data object Null : TemplatedType
    data object Nothing : TemplatedType
    companion object {
        val String = Complex(SignatureString("java::lang::String"), emptyList())
    }
    data class Generic(val name: String) : TemplatedType
    data class Array(val itemType: TemplatedType) : TemplatedType
    data class Complex(val signatureString: SignatureString, val generics: List<TemplatedType>) : TemplatedType
    data class Union(val types: Set<TemplatedType>) : TemplatedType
}

data class ForLoopConstruct(val parent: Expression, val name: String, val indexName: String?, val body: ConstructingArgument)

enum class ArrayType {
    Implicit,
    Int,
    Double,
    Bool,
    List
}

sealed interface Pattern {

    val bindingNames: Set<String>

    data class Const(val value: Expression.Const) : Pattern {
        override val bindingNames: Set<String> = emptySet()
    }
    data class Binding(val name: String) : Pattern {
        override val bindingNames: Set<String> = setOf(name)
    }
    data class Destructuring(val type: TemplatedType, val patterns: List<Pattern>) : Pattern {
        override val bindingNames: Set<String> = patterns.flatMap { it.bindingNames }.toSet() + if (type is TemplatedType.Complex && !type.signatureString.oxideNotation.contains("::")) setOf(type.signatureString.oxideNotation) else emptySet()
    }
    data class Conditional(val condition: Expression, val parent: Pattern) : Pattern {
        override val bindingNames: Set<String> = parent.bindingNames
    }
}

sealed interface Statement {
    data class Expr(val expression: Expression) : Statement
    data class While(val condition: Expression, val body: Expression): Statement
    data class Assign(val name: String, val value: Expression) : Statement
    data class AssignProperty(val parent: Expression, val name: String, val value: Expression) : Statement
    data class For(val forLoopConstruct: ForLoopConstruct) : Statement
    data class Return(val value: Expression?) : Statement
}

data class Module(val children: Map<String, ModuleChild>, val implBlocks: Map<TemplatedType, Impl>, val imports: Map<String, SignatureString>)

sealed interface ModuleChild {
    val modifiers: Modifiers
}

data class Function(val args: List<String>, val body: Expression, override val modifiers: Modifiers) : ModuleChild

data class Struct(val args: Map<String, TemplatedType>, val generics: Map<String, GenericType>, override val modifiers: Modifiers) : ModuleChild

data class Impl(
    val type: TemplatedType,
    val methods: Map<String, Function>,
    val associatedFunctions: Map<String, Function>,
    val generics: Map<String, GenericType>,
    override val modifiers: Modifiers
) : ModuleChild

data class UseStatement(val signatureStrings: Set<SignatureString>) : ModuleChild {
    override val modifiers: Modifiers = Modifiers.Empty
}

data class Variable(val initialValue: Expression, override val modifiers: Modifiers) : ModuleChild


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