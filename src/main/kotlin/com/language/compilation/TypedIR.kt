package com.language.compilation

import com.language.CompareOp
import com.language.MathOp
import com.language.codegen.VarFrame

sealed interface TypedInstruction {
    val type: Type

    sealed interface Const : TypedInstruction

    sealed interface PrimitiveConst : Const

    data class LoadConstString(val value: String): PrimitiveConst {
        override val type: Type = Type.String
    }
    data class LoadConstInt(val value: Int): PrimitiveConst {
        override val type: Type = Type.IntT
    }
    data class LoadConstDouble(val value: Double): PrimitiveConst {
        override val type: Type = Type.DoubleT
    }
    data class LoadConstBoolean(val value: Boolean): PrimitiveConst {
        override val type: Type = if(value) Type.BoolTrue else Type.BoolFalse
    }

    data class Try(val parent: TypedInstruction, val errorTypes: List<SignatureString>): TypedInstruction {
        override val type = parent.type
    }

    data class LoadList(val items: List<TypedConstructingArgument>, val itemType: Type.BroadType, val tempArrayVariable: Int?) : TypedInstruction {
        val isConstList = items.all { it is TypedConstructingArgument.Normal }
        override val type: Type = Type.BasicJvmType(
            SignatureString("java::util::ArrayList"),
            linkedMapOf("E" to itemType)
        )
    }

    data class LoadArray(val items: List<TypedConstructingArgument>, val arrayType: ArrayType, val itemType: Type.BroadType, val tempIndexVarId: Int, val tempArrayVarId: Int) : TypedInstruction {
        override val type: Type = when(arrayType) {
            ArrayType.Object -> Type.Array(itemType.mapKnown { it.asBoxed() })
            ArrayType.Int -> Type.IntArray
            ArrayType.Double -> Type.DoubleArray
            ArrayType.Bool -> Type.BoolArray
        }
    }


    data class ForLoop(
        val parent: TypedInstruction,
        val itemId: Int,
        val indexId: Int?,
        val hasNextCall: FunctionCandidate,
        val nextCall: FunctionCandidate,
        val body: TypedConstructingArgument,
        val preLoopAdjustments: ScopeAdjustment,
        val bodyFrame: VarFrame
    ) : TypedInstruction {
        override val type: Type = Type.Nothing
    }

    data class Keep(
        val value: TypedInstruction,
        val fieldName: String,
        val parentName: SignatureString
    ): TypedInstruction {
        override val type: Type
            get() = value.type.asBoxed()
    }

    data class LoadConstArray(val items: List<TypedInstruction>, val arrayType: ArrayType, val itemType: Type.BroadType) : TypedInstruction {
        override val type: Type = when(arrayType) {
            ArrayType.Object -> Type.Array(itemType.mapKnown { it.asBoxed() })
            ArrayType.Int -> Type.IntArray
            ArrayType.Double -> Type.DoubleArray
            ArrayType.Bool -> Type.BoolArray
        }
    }

    data class LoadConstConstArray(val items: List<Const>, val arrayType: ArrayType, val itemType: Type.BroadType): Const {
        override val type: Type = when(arrayType) {
            ArrayType.Object -> Type.Array(itemType.mapKnown { it.asBoxed() })
            ArrayType.Int -> Type.IntArray
            ArrayType.Double -> Type.DoubleArray
            ArrayType.Bool -> Type.BoolArray
        }
    }

    data class DynamicCall(
        val candidate: FunctionCandidate,
        val name: String,
        val parent: TypedInstruction,
        val args: List<TypedInstruction>,
        val commonInterface: Type.JvmType?
    ) : TypedInstruction {
        override val type: Type
            get() = candidate.oxideReturnType
    }

    data class StaticCall(
        val candidate: FunctionCandidate,
        val name: String,
        val parentName: SignatureString,
        val args: List<TypedInstruction>
    ) : TypedInstruction {
        override val type: Type
            get() = candidate.oxideReturnType
    }

    data class ModuleCall(
        val candidate: FunctionCandidate,
        val name: String,
        val parentName: SignatureString,
        val args: List<TypedInstruction>
    ) : TypedInstruction {
        override val type: Type
            get() = candidate.oxideReturnType
    }

    data class MultiInstructions(
        val instructions: List<TypedInstruction>,
        val varFrame: VarFrame
    ) : TypedInstruction {
        override val type: Type = instructions.lastOrNull()?.type ?: Type.Nothing
    }

    data class Math(
        val op: MathOp,
        val first: TypedInstruction,
        val second: TypedInstruction,
        override val type: Type
    ) : TypedInstruction

    data class Comparing(
        val first: TypedInstruction,
        val second: TypedInstruction,
        val op: CompareOp
    ) : TypedInstruction {
        override val type: Type = Type.BoolUnknown
    }

    data class ConstructorCall(
        val className: SignatureString,
        val args: List<TypedInstruction>,
        val candidate: FunctionCandidate,
        override val type: Type
    ) : TypedInstruction

    data class If(
        val cond: TypedInstruction,
        val body: TypedInstruction,
        val elseBody: TypedInstruction?,
        val typeConversionsBody: Map<String, Pair<Type, Type>>,
        val typeConversionsElseBody: Map<String, Pair<Type, Type>>
    ) : TypedInstruction {
        override val type: Type =when {
            body.type == Type.Nothing && (elseBody?.type ?: Type.Nothing) == Type.Nothing -> Type.Nothing
            body.type == elseBody?.type -> body.type
            else -> body.type.asBoxed().join(elseBody?.type?.asBoxed() ?: Type.Null)
        }

    }

    data class Lambda(
        val captures: Map<String, TypedInstruction>,
        val signatureString: SignatureString,
        val constructorCandidate: FunctionCandidate, //the candidate for the constructor call (when we actually need a runtime instance)
        val body: Instruction, // the reason we also carry the body itself is just so we can potentially inline
        val args: List<String> // the same reasoning applies to the arg names
    ): PrimitiveConst {
        override val type: Type = Type.Lambda(signatureString)
    }

    data class Match(
        val parent: TypedInstruction,
        val patterns: List<Triple<TypedIRPattern, TypedInstruction, VarFrame>>
    ) : TypedInstruction {
        override val type: Type
            get() = patterns.map { it.second.type }.reduce { acc, type -> acc.join(type)  }.let { if (it is Type.Union) it.asBoxed() else it }.simplifyUnbox()
    }

    data class While(
        val cond: TypedInstruction,
        val body: TypedInstruction
    ) : TypedInstruction {
        override val type: Type = Type.Nothing
    }

    data class StoreVar(val id: Int, val value: TypedInstruction) : TypedInstruction {
        override val type: Type = Type.Nothing
    }

    data class LoadVar(val id: Int, override val type: Type) : TypedInstruction

    data class DynamicPropertyAccess(val parent: TypedInstruction, val name: String, override val type: Type) : TypedInstruction

    data class DynamicPropertyAssignment(val parent: TypedInstruction, val name: String, val value: TypedInstruction,) : TypedInstruction {
        override val type: Type = Type.Nothing
    }


    data class StaticPropertyAccess(val parentName: SignatureString, val name: String, override val type: Type): TypedInstruction

    data object Pop : TypedInstruction {
        override val type: Type = Type.Nothing
    }
    data object Null : TypedInstruction {
        override val type: Type = Type.Null
    }

    //Doesn't for the type to be correct!!!
    data class Dup(override val type: Type): TypedInstruction
    data class Noop(override val type: Type): TypedInstruction
}


sealed interface TypedConstructingArgument {
    val instruction: TypedInstruction
    val type: Type
    @JvmInline
    value class Normal(override val instruction: TypedInstruction) : TypedConstructingArgument {
        override val type: Type
            get() = instruction.type
    }

    @JvmInline
    value class Collected(override val instruction: TypedInstruction) : TypedConstructingArgument {
        override val type: Type
            get() = instruction.type
    }

    @JvmInline
    value class Iteration(override val instruction: TypedInstruction.ForLoop): TypedConstructingArgument {
        override val type: Type
            get() = instruction.body.type
    }
}

sealed interface TypedIRPattern {

    val bindings: Set<Pair<String, Type>>

    data class Binding(val name: String, val id: Int, val origin: TypedInstruction): TypedIRPattern {
        override val bindings: Set<Pair<String, Type>> = setOf(name to origin.type)
    }
    data class Destructuring(val type: Type, val patterns: List<TypedIRPattern>, val origin: TypedInstruction, val isLast: Boolean, val castStoreId: Int?) : TypedIRPattern {
        override val bindings: Set<Pair<String, Type>> = patterns.flatMap { it.bindings }.toSet()
    }
    data class Condition(val parent: TypedIRPattern, val condition: TypedInstruction) : TypedIRPattern {
        override val bindings: Set<Pair<String, Type>> = parent.bindings
    }
}

data class ScopeAdjustment(
    val instructions: List<ScopeAdjustInstruction>
)

sealed interface ScopeAdjustInstruction {
    data class Move(
        val src: Int,
        val dest: Int,
        val type: Type
    ) : ScopeAdjustInstruction

    data class Box(
        val src: Int,
        val type: Type
    ) : ScopeAdjustInstruction

    data class Unbox(
        val src: Int,
        val type: Type
    ) : ScopeAdjustInstruction

    data class Store(
        val value: TypedInstruction,
        val dest: Int
    ) : ScopeAdjustInstruction
}


