package com.language.codegen

import com.language.compilation.Type
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.math.max

interface StackMap {
    fun push(type: Type)

    /**
     * @throws IllegalStateException
     */
    fun pop(): Type
    fun pop(n: Int): Unit = (1..n).forEach { _ -> pop() }

    val stackSize: Int

    fun pushVarFrame(varFrame: VarFrame, cloning: Boolean = false)

    fun popVarFrame()

    fun changeVar(id: Int, type: Type)

    fun adaptFrame(varFrame: VarFrame)
    fun generateFrame(mv: MethodVisitor)
    fun generateFrame(mv: MethodVisitor, varFrame: VarFrame)
    fun dup()
    fun swap()

    companion object {
        fun fromMax(maxStack: Int): StackMap {
            return StackMapImpl(Array(maxStack) { null })
        }
    }
}

interface VarFrame {
    val variables: List<Type?>

    fun collapse(other: VarFrame): VarFrame {
        val newVariables: Array<Type?> = arrayOfNulls(max(other.variables.size, variables.size))
        other.variables.forEachIndexed { i, type -> if (type != null) newVariables[i] = type }
        variables.forEachIndexed { i, type -> if(type != null) newVariables[i] = type }
        return VarFrameImpl(newVariables.toList())
    }
}

data class MutableVarFrame(
    override var variables: MutableList<Type?>
) : VarFrame {

    fun change(id: Int, type: Type) {
        while (id >= variables.size) {
            variables.add(null)
        }

        variables[id] = type
    }

}

data class VarFrameImpl(
    override val variables: List<Type?>
) : VarFrame

class StackMapImpl(
    private val stack: Array<Type?>,
) : StackMap {
    private var currentStackPtr = 0

    override val stackSize: Int
        get() = currentStackPtr

    private val varFrames: MutableList<VarFrame> = mutableListOf()
    private val mutableVarFrames: MutableList<MutableVarFrame> = mutableListOf()

    override fun swap() {
        val latest = stack[currentStackPtr-1]!!
        val old = stack[currentStackPtr-2]!!
        stack[currentStackPtr-1] = old
        stack[currentStackPtr-2] = latest
    }

    override fun push(type: Type) {
        if (type == Type.Nothing || type == Type.Never) return
        if (currentStackPtr == stack.lastIndex) {
            error("Stackoverflow (Stack si1ze too small) (Internal Error) ${stack.contentDeepToString()}")
        }
        stack[currentStackPtr++] = type
    }

    override fun dup() {
        val value = stack[currentStackPtr-1]!!
        push(value)
    }

    override fun pop(): Type {
        if (currentStackPtr < 1) {
            error("Cannot pop of empty stack")
        }
        val item = stack[--currentStackPtr]
        stack[currentStackPtr] = null
        return item!!
    }

    override fun pushVarFrame(varFrame: VarFrame, cloning: Boolean) {
        varFrames.add(varFrame)
        if (cloning) {
            mutableVarFrames.add(MutableVarFrame(varFrame.variables.toMutableList()))
            return
        }
        mutableVarFrames.add(MutableVarFrame(ArrayList(varFrame.variables.size)))

    }

    override fun popVarFrame() {
        varFrames.removeLast()
        mutableVarFrames.removeLast()
    }

    override fun changeVar(id: Int, type: Type) {
        mutableVarFrames.last().change(id, type)
    }

    override fun adaptFrame(varFrame: VarFrame) {
        mutableVarFrames.last().variables = varFrame.variables.toMutableList()
    }


    //private val frameHistory: MutableList<StackFrame> = mutableListOf()

    private var previousVariables: VarFrame? = null
    private var previousStack: List<Type?>? = null

    override fun generateFrame(mv: MethodVisitor) {

        val variables = mutableVarFrames.fold(VarFrameImpl(emptyList()) as VarFrame) { acc, frame ->
            frame.collapse(acc)
        }

        /*
        if (variables.variables == previousVariables?.variables && stack.toList() == previousStack) {
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
            return
        }

        previousVariables = variables
        previousStack = stack.toList()


         */
        mv.visitFrame(
            Opcodes.F_FULL,
            variables.variables.size,
            variables.variables.map { it?.toFrameSignature() ?: Opcodes.TOP }.toTypedArray(),
            currentStackPtr,
            stack.map { it?.toFrameSignature() }.toTypedArray()
        )

    }

    override fun generateFrame(mv: MethodVisitor, varFrame: VarFrame) {
        //val stackFrame = StackFrame(varFrame.variables, stack)

        /*if (stackFrame == frameHistory.lastOrNull()) {
            mv.visitFrame(
                Opcodes.F_SAME,
                0,
                null,
                0,
                null
            )
            return
        }

        frameHistory.add(stackFrame)

         */
        try {

            mv.visitFrame(
                Opcodes.F_FULL,
                varFrame.variables.size,
                varFrame.variables.map { it?.toFrameSignature() }.toTypedArray(),
                currentStackPtr,
                stack.map { it?.toFrameSignature() }.toTypedArray()
            )
        }catch (_: Exception) {}
    }
}

data class StackFrame(val variables: List<Type?>, val stack: Array<Type?>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StackFrame

        if (variables != other.variables) return false
        if (!stack.contentEquals(other.stack)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = variables.hashCode()
        result = 31 * result + stack.contentHashCode()
        return result
    }
}

fun Type.toFrameSignature(): Any = when(this) {
    is Type.BoolT -> Opcodes.INTEGER
    Type.DoubleT -> Opcodes.DOUBLE
    Type.IntT -> Opcodes.INTEGER
    is Type.Lambda -> signature.toJvmNotation()
    Type.Never -> error("Never can't be on a stack")
    is Type.BasicJvmType -> signature.toJvmNotation()
    Type.Nothing -> error("Nothing can't be on a stack")
    Type.Null -> "java/lang/Object"
    is Type.Union -> "java/lang/Object"
    is Type.Array -> "[Ljava/lang/Object;"
    Type.BoolArray -> "[Z"
    Type.DoubleArray -> "[D"
    Type.IntArray -> "[I"
}