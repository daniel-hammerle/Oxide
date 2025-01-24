package com.language.codegen

import com.language.compilation.LambdaContainer
import com.language.compilation.Type
import com.language.lookup.oxide.lazyTransform
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

suspend fun compileLambda(lambda: LambdaContainer): ByteArray {
    val cw = ClassWriter(0)

    cw.visit(
        62,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        lambda.signature.toJvmNotation(),
        null,
        "java/lang/Object",
        emptyArray()
    )


    lambda.captures.forEach { (name, type) ->
        cw.visitField(
            Opcodes.ACC_PRIVATE,
            name,
            type.type.toJVMDescriptor(),
            null,
            null
        )
    }

    generateConstructor(cw, lambda.captures.mapValues { it.value.type }, lambda.signature)


    lambda.checkedVariants.map { (argTypes, body) ->
        compileCheckedFunction(cw, "invoke", body.first,body.second, argTypes, static = false, instanceType = Type.Lambda(lambda.signature))
    }

    return cw.toByteArray()
}