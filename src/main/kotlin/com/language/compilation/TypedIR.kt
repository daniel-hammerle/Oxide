package com.language.compilation

import com.language.BooleanOp
import com.language.CompareOp
import com.language.MathOp
import com.language.codegen.VarFrame
import com.language.codegen.asUnboxedOrIgnore
import com.language.compilation.tracking.*
import org.objectweb.asm.Label

sealed interface TypedInstruction {
    val forge: InstanceForge
    val type: Type
        get() = forge.type

    sealed interface Const : TypedInstruction

    sealed interface PrimitiveConst : Const

    data class LoadConstString(val value: String): PrimitiveConst {
        override val forge: InstanceForge = InstanceForge.ConstString
    }
    data class LoadConstInt(val value: Int): PrimitiveConst {
        override val forge: InstanceForge = InstanceForge.ConstInt
    }
    data class LoadConstDouble(val value: Double): PrimitiveConst {
        override val forge: InstanceForge = InstanceForge.ConstDouble
    }
    data class LoadConstBoolean(val value: Boolean): PrimitiveConst {
        override val forge: InstanceForge =  if (value) InstanceForge.ConstBoolTrue else InstanceForge.ConstBoolFalse
    }

    data class Ignore(val other: TypedInstruction) : TypedInstruction {
        override val forge: InstanceForge = InstanceForge.ConstNothing
    }

    data class InlineBody(val body: TypedInstruction, val endLabel: Label, override val forge: InstanceForge): TypedInstruction

    data class Try(val parent: TypedInstruction, val errorTypes: List<SignatureString>): TypedInstruction {
        override val forge: InstanceForge = parent.forge
    }


    data class Return(val returnValue: TypedInstruction, val label: Label?): TypedInstruction {
        override val forge: InstanceForge
            get() = InstanceForge.ConstNever
    }

    data class LoadList(val items: List<TypedConstructingArgument>, val itemType: Type.Broad, val tempArrayVariable: Int?) : TypedInstruction {
        val isConstList = items.all { it is TypedConstructingArgument.Normal }
        override val forge: InstanceForge = JvmInstanceForge(
            mutableMapOf("E" to BroadForge.Empty),
            SignatureString("java::util::ArrayList")
        )
    }

    data class LoadArray(val items: List<TypedConstructingArgument>, val arrayType: ArrayType, val itemType: Type.Broad, val tempIndexVarId: Int, val tempArrayVarId: Int) : TypedInstruction {
        override val type: Type = when(arrayType) {
            ArrayType.Object -> Type.Array(itemType.mapKnown { it.asBoxed() })
            ArrayType.Int -> Type.IntArray
            ArrayType.Double -> Type.DoubleArray
            ArrayType.Bool -> Type.BoolArray
        }
        override val forge: InstanceForge = BasicInstanceForge(type)
    }


    data class ForLoop(
        val parent: TypedInstruction,
        val itemId: Int,
        val indexId: Int?,
        val hasNextCall: FunctionCandidate,
        val nextCall: FunctionCandidate,
        val body: TypedConstructingArgument,
        val preLoopAdjustments: ScopeAdjustment,
        val postLoopAdjustments: ScopeAdjustment,
        val bodyFrame: VarFrame
    ) : TypedInstruction {
        override val forge: InstanceForge = InstanceForge.ConstNothing
    }

    data class Keep(
        val value: TypedInstruction,
        val fieldName: String,
        val parentName: SignatureString
    ): TypedInstruction {
        override val type: Type
            get() = value.type
        override val forge: InstanceForge
            get() = value.forge
    }

    data class LoadConstArray(val items: List<TypedInstruction>, val arrayType: ArrayType, val itemType: Type.Broad) : TypedInstruction {
        override val type: Type = when(arrayType) {
            ArrayType.Object -> Type.Array(itemType.mapKnown { it.asBoxed() })
            ArrayType.Int -> Type.IntArray
            ArrayType.Double -> Type.DoubleArray
            ArrayType.Bool -> Type.BoolArray
        }
        override val forge: InstanceForge = BasicInstanceForge(type)
    }

    data class LoadConstConstArray(val items: List<Const>, val arrayType: ArrayType, val itemType: Type.Broad): Const {
        override val type: Type = when(arrayType) {
            ArrayType.Object -> Type.Array(itemType.mapKnown { it.asBoxed() })
            ArrayType.Int -> Type.IntArray
            ArrayType.Double -> Type.DoubleArray
            ArrayType.Bool -> Type.BoolArray
        }
        override val forge: InstanceForge = BasicInstanceForge(type)
    }

    data class DynamicCall(
        val candidate: FunctionCandidate,
        val name: String,
        val parent: TypedInstruction,
        val args: List<TypedInstruction>,
        val commonInterface: Type.JvmType?
    ) : TypedInstruction {
        override val forge: InstanceForge
            get() = candidate.returnForge
        override val type: Type = candidate.oxideReturnType
    }

    data class StaticCall(
        val candidate: FunctionCandidate,
        val name: String,
        val parentName: SignatureString,
        val args: List<TypedInstruction>
    ) : TypedInstruction {
        override val forge: InstanceForge
            get() = candidate.returnForge
        override val type: Type = candidate.oxideReturnType
    }

    data class ModuleCall(
        val candidate: FunctionCandidate,
        val name: String,
        val parentName: SignatureString,
        val args: List<TypedInstruction>
    ) : TypedInstruction {
        override val forge: InstanceForge
            get() = candidate.returnForge
        override val type: Type = candidate.oxideReturnType
    }

    data class MultiInstructions(
        val instructions: List<TypedInstruction>,
        val varFrame: VarFrame
    ) : TypedInstruction {
        override val forge: InstanceForge = instructions.lastOrNull()?.forge ?: InstanceForge.ConstNothing
    }

    data class Math(
        val op: MathOp,
        val first: TypedInstruction,
        val second: TypedInstruction,
        override val forge: InstanceForge
    ) : TypedInstruction

    data class Comparing(
        val first: TypedInstruction,
        val second: TypedInstruction,
        val op: CompareOp
    ) : TypedInstruction {
        override val forge: InstanceForge
            get() = InstanceForge.ConstBool
    }

    data class ConstructorCall(
        val className: SignatureString,
        val args: List<TypedInstruction>,
        val candidate: FunctionCandidate,
    ) : TypedInstruction {
        override val forge: InstanceForge
            get() = candidate.returnForge
        override val type: Type = candidate.oxideReturnType
    }

    data class If(
        val cond: TypedInstruction,
        val body: TypedInstruction,
        val elseBody: TypedInstruction?,
        val bodyAdjust: ScopeAdjustment,
        val elseBodyAdjust: ScopeAdjustment,
        val varFrame: VarFrame
    ) : TypedInstruction {
        override val type: Type =when {
            body.type == Type.Nothing && (elseBody?.type ?: Type.Nothing) == Type.Nothing -> Type.Nothing
            body.type == elseBody?.type -> body.type
            else -> body.type.asBoxed().join(elseBody?.type?.asBoxed() ?: Type.Null).asUnboxedOrIgnore()
        }

        override val forge: InstanceForge
            get() = if (elseBody != null) body.forge.join(elseBody.forge) else body.forge

    }

    data class Not(
        val ins: TypedInstruction
    ) : TypedInstruction {
        override val forge: InstanceForge = InstanceForge.ConstBool
    }

    data class LogicOperation(
        val first: TypedInstruction,
        val second: TypedInstruction,
        val op: BooleanOp
    ) : TypedInstruction {
        override val forge: InstanceForge = InstanceForge.ConstBool
    }

    data class Lambda(
        val captures: Map<String, TypedInstruction>,
        val signatureString: SignatureString,
        val constructorCandidate: FunctionCandidate, //the candidate for the constructor call (when we actually need a runtime instance)
        val body: Instruction, // the reason the body itself is carried is potential inlining
        val args: List<String> // the same reasoning applies to the arg names
    ): PrimitiveConst {
        override val type: Type = Type.Lambda(signatureString)
        override val forge: InstanceForge = InstanceForge.make(type)
    }

    data class Match(
        val parent: TypedInstruction,
        val patterns: List<CompiledMatchBranch>,
        val temporaryId: Int
    ) : TypedInstruction {
        override val type: Type
            get() = patterns.map { it.body.type }.reduce { acc, type -> acc.join(type)  }.let { if (it is Type.Union) it.asBoxed() else it }.simplifyUnbox()
        override val forge: InstanceForge = patterns.map { it.body.forge }.reduce { acc, forge -> acc.join(forge) }
    }

    data class While(
        val cond: TypedInstruction,
        val body: TypedInstruction,
        val infinite: Boolean,
    ) : TypedInstruction {
        override val forge: InstanceForge = if (infinite) InstanceForge.ConstNever else InstanceForge.ConstNothing
    }

    data class StoreVar(val id: Int, val value: TypedInstruction) : TypedInstruction {
        override val forge: InstanceForge = InstanceForge.ConstNothing
    }

    data class LoadVar(val id: Int, override val forge: InstanceForge) : TypedInstruction

    data class DynamicPropertyAccess(val parent: TypedInstruction, val name: String, override val forge: InstanceForge, val physicalType: Type) : TypedInstruction

    data class DynamicPropertyAssignment(val parent: TypedInstruction, val name: String, val value: TypedInstruction, val physicalType: Type) : TypedInstruction {
        override val forge: InstanceForge
            get() = InstanceForge.ConstNothing
    }


    data class StaticPropertyAccess(val parentName: SignatureString, val name: String, override val forge: InstanceForge, override val type: Type): TypedInstruction

    data object Pop : TypedInstruction {
        override val forge: InstanceForge = InstanceForge.ConstNothing
    }
    data object Null : TypedInstruction {
        override val forge: InstanceForge = InstanceForge.ConstNull
    }

    //Doesn't for the type to be correct!!!
    data class Noop(override val type: Type, override val forge: InstanceForge = BasicInstanceForge(type)): TypedInstruction {
    }
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

    data class Binding(val name: String, val type: Type): TypedIRPattern {
        override val bindings: Set<Pair<String, Type>> = setOf(name to type)
    }
    data class Destructuring(
        val forge: InstanceForge,
        val patterns: List<TypedIRPattern>,
        val loadItem: TypedInstruction,
        val isLast: Boolean,
        val castStoreId: Int
    ) : TypedIRPattern {
        override val bindings: Set<Pair<String, Type>> = patterns.flatMap { it.bindings }.toSet()
    }
    data class Condition(val parent: TypedIRPattern, val condition: TypedInstruction) : TypedIRPattern {
        override val bindings: Set<Pair<String, Type>> = parent.bindings
    }
}

data class CompiledMatchBranch(
    val pattern: TypedIRPattern,
    val body: TypedInstruction,
    val scopeAdjustment: ScopeAdjustment,
    val varFrame: VarFrame
)

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


