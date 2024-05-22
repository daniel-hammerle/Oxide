package com.language.codegen

import com.language.compilation.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

suspend fun compileCheckedFunction(
    cw: ClassWriter,
    name: String,
    body: TypedInstruction,
    metaData: FunctionMetaData,
    argTypes: List<Type>,
) {
    val mv = cw.visitMethod(
        Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC,
        jvmName(name, argTypes),
        generateJVMFunctionSignature(argTypes, metaData.returnType),
        null,
        arrayOf()
    )
    mv.visitMaxs(20, metaData.varCount + 1) // 1 for the instance
    val stackMap = StackMap.fromMax(20)
    stackMap.pushVarFrame(VarFrameImpl(argTypes), true)
    //compile body
    compileInstruction(mv, body, stackMap)

    when(body.type) {
        Type.Nothing -> assert(stackMap.stackSize == 0)
        else -> assert(stackMap.stackSize == 1)
    }

    //return
    when(body.type) {
        is Type.JvmType, Type.Null, is Type.Union -> mv.visitInsn(Opcodes.ARETURN)
        Type.IntT,is Type.BoolT -> mv.visitInsn(Opcodes.IRETURN)
        Type.DoubleT -> mv.visitInsn(Opcodes.DRETURN)
        Type.Nothing -> mv.visitInsn(Opcodes.RETURN)
        is Type.Array -> mv.visitInsn(Opcodes.ARETURN)
        Type.Never -> mv.visitInsn(Opcodes.RETURN)
    }
}

fun jvmName(name: String, argTypes: List<Type>): String {
    return "${name}_${argTypes.hashCode().toLong() and 0xFFFFFFFFL}"
}

fun Type.BroadType.toFunctionNameNotation() = when(this) {
    is Type.BroadType.Known -> type.toFunctionNameNotation()
    Type.BroadType.Unset -> "unknown"
}

fun Type.BroadType.getOrDefault(type: Type): Type = when(this) {
    is Type.BroadType.Known -> this.type
    Type.BroadType.Unset -> type
}

fun Type.toFunctionNameNotation(): String = when(this) {
    is Type.BoolT -> "z"
    Type.DoubleT -> "d"
    Type.IntT -> "i"
    is Type.BasicJvmType -> "${signature.toJvmNotation()}${genericTypes.values.joinToString("") { it.toFunctionNameNotation() }}"
    Type.Nothing -> "v"
    Type.Never -> "v"
    Type.Null -> "n"
    is Type.Array -> "${itemType.getOrDefault(Type.Object).toJVMDescriptor().removeSuffix(";").removePrefix("L")}]"
    is Type.Union -> "u(${entries.joinToString("") { it.toFunctionNameNotation() }})"
}