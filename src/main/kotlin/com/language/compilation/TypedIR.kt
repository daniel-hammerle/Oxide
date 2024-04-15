package com.language.compilation

import com.language.CompareOp
import com.language.MathOp
import com.language.codegen.VarFrame

sealed interface TypedInstruction {
    val type: Type

    data class LoadConstString(val value: String): TypedInstruction {
        override val type: Type = Type.String
    }
    data class LoadConstInt(val value: Int): TypedInstruction {
        override val type: Type = Type.IntT
    }
    data class LoadConstDouble(val value: Double): TypedInstruction {
        override val type: Type = Type.DoubleT
    }
    data class LoadConstBoolean(val value: Boolean): TypedInstruction {
        override val type: Type = Type.BoolT
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
        override val type: Type = Type.BoolT
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
        override val type: Type = body.type.join(elseBody?.type ?: Type.Null)
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

    data class StaticPropertyAccess(val parentName: SignatureString, val name: String, override val type: Type): TypedInstruction

    data object Pop : TypedInstruction {
        override val type: Type = Type.Nothing
    }
    data object Null : TypedInstruction {
        override val type: Type = Type.Null
    }
}