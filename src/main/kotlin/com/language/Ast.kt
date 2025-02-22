package com.language

import com.language.compilation.GenericType
import com.language.compilation.SignatureString
import com.language.compilation.modifiers.Modifiers
import com.language.lexer.MetaInfo

sealed interface Expression {

    val info: MetaInfo

    sealed interface Const : Expression
    data class ConstNull(override val info: MetaInfo) : Const

    data class ConstNum(val num: Double, override val info: MetaInfo) : Const
    data class ConstStr(val str: String, override val info: MetaInfo) : Const
    data class ConstBool(val bool: Boolean, override val info: MetaInfo) : Const

    sealed interface Array : Expression {
        val arrayType: ArrayType
    }


    data class Intrinsic(val name: String, override val info: MetaInfo) : Expression

    data class ConstArray(
        override val arrayType: ArrayType,
        val items: List<ConstructingArgument>,
        override val info: MetaInfo
    ): Array

    data class DefaultArray(
        override val arrayType: ArrayType,
        val defaultItem: Expression,
        val size: Expression,
        override val info: MetaInfo
    ) : Array
    data class CollectorArray(
        override val arrayType: ArrayType,
        val name: String,
        val parent: Expression,
        val body: ConstructingArgument,
        override val info: MetaInfo
    ): Array

    data class Invoke(
        val parent: Expression,
        val args: Map<String, Expression>,
        override val info: MetaInfo
    ): Expression

    data class AccessProperty(
        val parent: Expression,
        override val name: String,
        override val info: MetaInfo
    ) : Invokable

    /**
     * @param accessor The expression used to access the array, which should either be a range for slicing or an index
     */
    data class ArrayAccess(
        val accessor: Expression,
        override val info: MetaInfo
    ) : Expression

    sealed interface Invokable : Expression {
        val name: String
    }
    data class UnknownSymbol(
        val sigName: String,
        override val info: MetaInfo
    ): Invokable {
        override val name: String = sigName
    }

    data class VariableSymbol(override val name: String, override val info: MetaInfo): Invokable

    data class Try(
        val expression: Expression,
        override val info: MetaInfo
    ): Expression

    data class Catch(
        val expression: Expression,
        val type: TemplatedType?,
        override val info: MetaInfo
    ) : Expression

    data class Math(
        val first: Expression,
        val second: Expression,
        val op: MathOp,
        override val info: MetaInfo
    ) : Expression

    data class Comparing(
        val first: Expression,
        val second: Expression,
        val op: CompareOp,
        override val info: MetaInfo
    ) : Expression

    data class BooleanOperation(
        val first: Expression,
        val second: Expression,
        val op: BooleanOp,
        override val info: MetaInfo
    ): Expression

    data class Not(
        val expr: Expression,
        override val info: MetaInfo
    ): Expression

    data class ReturningScope(
        val expressions: List<Statement>,
        override val info: MetaInfo
    ) : Expression

    data class Lambda(
        val args: List<String>,
        val body: Expression,
        val capturedVariables: Set<String>,
        override val info: MetaInfo
    ): Expression

    data class IfElse(
        val condition: Expression,
        val body: Expression,
        val elseBody: Expression?,
        override val info: MetaInfo
    ) : Expression

    data class Match(
        val matchable: Expression,
        val branches: List<Pair<Pattern, Expression>>,
        override val info: MetaInfo
    ) : Expression

    data class Keep(val value: Expression, override val info: MetaInfo): Expression
}

enum class BooleanOp {
    And,
    Or,
}

sealed interface Range : Expression {
    data class Normal(
        val lower: Expression,
        val upper: Expression,
        val upperInclusive: Boolean,
        override val info: MetaInfo
    ): Range

    data class UntilUpper(
        val upper: Expression,
        val upperInclusive: Boolean,
        override val info: MetaInfo
    ): Range
    data class FromLower(val lower: Expression, override val info: MetaInfo): Range
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

    data object Never : TemplatedType
    data class Generic(val name: String) : TemplatedType
    data class Array(val itemType: TemplatedType) : TemplatedType
    data class Complex(val signatureString: SignatureString, val generics: List<TemplatedType>) : TemplatedType
    data class Union(val types: Set<TemplatedType>) : TemplatedType
}

data class ForLoopConstruct(val parent: Expression, val name: String, val indexName: String?, val body: ConstructingArgument, val info: MetaInfo)

enum class ArrayType {
    Implicit,
    Int,
    Double,
    Bool,
    List
}

sealed interface Pattern {
    val info: MetaInfo
    val bindingNames: Set<String>

    data class Const(val value: Expression.Const, override val info: MetaInfo) : Pattern {
        override val bindingNames: Set<String> = emptySet()
    }
    data class Binding(val name: String, override val info: MetaInfo) : Pattern {
        override val bindingNames: Set<String> = setOf(name)
    }
    data class Destructuring(val type: TemplatedType, val patterns: List<Pattern>, override val info: MetaInfo) : Pattern {
        override val bindingNames: Set<String> = patterns.flatMap { it.bindingNames }.toSet() + if (type is TemplatedType.Complex && !type.signatureString.oxideNotation.contains("::")) setOf(type.signatureString.oxideNotation) else emptySet()
    }
    data class Conditional(val condition: Expression, val parent: Pattern, override val info: MetaInfo) : Pattern {
        override val bindingNames: Set<String> = parent.bindingNames
    }
}

sealed interface Statement {
    val info: MetaInfo
    data class Expr(val expression: Expression) : Statement {
        override val info: MetaInfo
            get() = expression.info
    }
    data class While(val condition: Expression, val body: Expression, override val info: MetaInfo): Statement
    data class Assign(val name: String, val value: Expression, override val info: MetaInfo) : Statement
    data class AssignProperty(
        val parent: Expression,
        val name: String,
        val value: Expression,
        override val info: MetaInfo
    ) : Statement
    data class For(val forLoopConstruct: ForLoopConstruct, override val info: MetaInfo) : Statement
    data class Return(val value: Expression?, override val info: MetaInfo) : Statement
}

data class Module(
    val children: Map<String, ModuleChild>,
    val implBlocks: Map<TemplatedType, Impl>,
    val imports: Map<String, SignatureString>,
    val typeAliases: Map<String, TypeDef>
)

sealed interface ModuleChild {
    val modifiers: Modifiers
    val info: MetaInfo
}

data class Function(
    val args: List<Pair<String, TemplatedType?>>,
    val returnType: TemplatedType?,
    val generics: List<Pair<String, GenericType>>,
    val body: Expression,
    override val modifiers: Modifiers,
    override val info: MetaInfo
) : ModuleChild

data class Struct(
    val args: List<Pair<String, TemplatedType?>>,
    val generics: Map<String, GenericType>,
    override val modifiers: Modifiers,
    override val info: MetaInfo
) : ModuleChild

data class TypeDef(
    val generics: List<Pair<String, GenericType>>,
    val type: TemplatedType,
    val name: String,
    override val modifiers:
    Modifiers, override val info: MetaInfo
): ModuleChild

data class Impl(
    val type: TemplatedType,
    val methods: Map<String, Function>,
    val associatedFunctions: Map<String, Function>,
    val generics: Map<String, GenericType>,
    override val modifiers: Modifiers,
    override val info: MetaInfo
) : ModuleChild

data class UseStatement(val signatureStrings: Set<SignatureString>, override val info: MetaInfo) : ModuleChild {
    override val modifiers: Modifiers = Modifiers.Empty
}

data class Variable(val initialValue: Expression, override val modifiers: Modifiers, override val info: MetaInfo) : ModuleChild


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