package com.language.wasm

import com.language.MathOp

sealed interface WasmInstruction {
    val type: WasmType

    data class ContainerAlloc(val bytes: Int) : WasmInstruction {
        override val type: WasmType
            get() = WasmType.Ptr
    }

    data class Block(val instructions: Array<WasmInstruction>, val name: String) : WasmInstruction {
        override val type: WasmType
            get() = instructions.lastOrNull()?.type ?: WasmType.Nothing

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Block

            return instructions.contentEquals(other.instructions)
        }

        override fun hashCode(): Int {
            return instructions.contentHashCode()
        }
    }

    data class Break(val name: String, val value: WasmInstruction?) : WasmInstruction {
        override val type: WasmType
            get() = WasmType.Never

    }

    data class If(val condition: WasmInstruction, val body: WasmInstruction, val elseBody: WasmInstruction?): WasmInstruction {
        override val type: WasmType
            get() = body.type

        init {
            assert(condition.type == WasmType.Bool)
        }

    }


    data class LoadI32(val number: Int) : WasmInstruction {
        override val type: WasmType
            get() = WasmType.I32
    }


    data class LoadF64(val number: Double) : WasmInstruction {
        override val type: WasmType
            get() = WasmType.F64
    }

    data class LoadBool(val bool: Boolean) : WasmInstruction {
        override val type: WasmType
            get() = WasmType.Bool
    }

    data class Math(
        val first: WasmInstruction, val second: WasmInstruction, val op: MathOp, override val type: WasmType
    ) : WasmInstruction

    data class StoreLocal(val localTpId: Int, val value: WasmInstruction) : WasmInstruction {
        override val type: WasmType
            get() = value.type

    }

    data class LoadLocal(val localId: Int, override val type: WasmType) : WasmInstruction

    data class PropertyAccess(val pointer: WasmInstruction, val offset: Int, override val type: WasmType) :
        WasmInstruction

    data class PropertyAssignment(val pointer: WasmInstruction, val offset: Int, val value: WasmInstruction) :
        WasmInstruction {
        override val type: WasmType
            get() = WasmType.Nothing
    }

    data class StackAlloc(val amount: Int) : WasmInstruction {
        override val type: WasmType
            get() = WasmType.Ptr
    }

    data class StackFree(val amount: Int) : WasmInstruction {
        override val type: WasmType
            get() = WasmType.Nothing

    }
}


enum class WasmType {
    I32,
    F64,
    Bool,
    Str,
    Union,
    Ptr,
    Nothing,
    Never,
    I64,
    F32,
}

data class WasmFunction(
    val name: String,
    val arguments: Array<WasmType>,
    val returnType: WasmType, val body: WasmInstruction, val locals: Array<WasmType>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WasmFunction

        if (name != other.name) return false
        if (!arguments.contentEquals(other.arguments)) return false
        if (returnType != other.returnType) return false
        if (body != other.body) return false
        if (!locals.contentEquals(other.locals)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + arguments.contentHashCode()
        result = 31 * result + returnType.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + locals.contentHashCode()
        return result
    }


}

data class WasmModule(val name: String, val functions: Array<WasmFunction>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WasmModule

        if (name != other.name) return false
        if (!functions.contentEquals(other.functions)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + functions.contentHashCode()
        return result
    }
}