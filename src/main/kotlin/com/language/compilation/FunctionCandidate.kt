package com.language.compilation

import com.language.codegen.generateJVMFunctionSignature
import com.language.codegen.jvmName
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

data class FunctionCandidate(
    val oxideArgs: List<Type>,
    val jvmArgs: List<Type>,
    val jvmReturnType: Type,
    val oxideReturnType: Type,
    val invocationType: Int,
    val jvmOwner: SignatureString,
    val name: String,
    val obfuscateName: Boolean,
    val requireDispatch: Boolean
)

fun FunctionCandidate.generateCall(mv: MethodVisitor) {
    mv.visitMethodInsn(
        invocationType,
        jvmOwner.toJvmNotation(),
        if (obfuscateName) jvmName(name, oxideArgs) else name,
        generateJVMFunctionSignature(jvmArgs, jvmReturnType),
        invocationType == Opcodes.INVOKEINTERFACE
    )
}

fun FunctionCandidate.toJvmDescriptor() = generateJVMFunctionSignature(jvmArgs, jvmReturnType)

val FunctionCandidate.hasGenericReturnType: Boolean
    get() = jvmReturnType != oxideReturnType