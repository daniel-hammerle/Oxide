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
    static: Boolean = true,
    instanceType: Type? = null
) {
    val accessModifiers = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or if (static) Opcodes.ACC_STATIC else 0
    val methodName = jvmName(name, if (instanceType != null) listOf(instanceType) + argTypes else argTypes)
    val mv = cw.visitMethod(
        accessModifiers,
        methodName,
        generateJVMFunctionSignature(argTypes, metaData.returnType),
        null,
        arrayOf()
    )

    mv.visitMaxs(20, (if (!static) 1 else 0) + metaData.varCount)
    val stackMap = StackMap.fromMax(20)
    stackMap.pushVarFrame(VarFrameImpl(argTypes), true)
    if (instanceType != null) {
        stackMap.changeVar(0, instanceType)
    }

    //compile body
    compileInstruction(mv, body, stackMap, CleanupStack(mutableListOf()))

    when(body.type) {
        Type.Nothing, Type.Never-> assert(stackMap.stackSize == 0) { "Expected 0 but was ${stackMap.stackSize} ${body.type} $name" }
        else -> assert(stackMap.stackSize == 1) { "Expected 1 but was ${stackMap.stackSize} ${body.type} $name" }
    }

    //return
    if (body.type == Type.Never) {
        return
    }

    println(metaData.returnType)
    val ins = returnInstruction(metaData.returnType)
    mv.visitInsn(ins)
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

fun Type.BroadType.getOrNull(): Type? = when(this) {
    is Type.BroadType.Known -> this.type
    Type.BroadType.Unset -> null
}


fun Type.BroadType.getOrThrow(message: String) =when(this) {
    is Type.BroadType.Known -> this.type
    Type.BroadType.Unset -> error(message)
}

fun Type.toFunctionNameNotation(): String = when(this) {
    is Type.BoolT -> "z"
    Type.DoubleT -> "d"
    Type.IntT -> "i"
    is Type.Lambda -> "l"
    is Type.BasicJvmType -> "${signature.toJvmNotation()}${genericTypes.values.joinToString("") { it.toFunctionNameNotation() }}"
    Type.Nothing -> "v"
    Type.Never -> "v"
    Type.Null -> "n"
    is Type.JvmArray -> "${itemType.getOrDefault(Type.Object).toJVMDescriptor().removeSuffix(";").removePrefix("L")}]"
    is Type.Union -> "u(${entries.joinToString("") { it.toFunctionNameNotation() }})"
}