package com.language.codegen

import com.language.compilation.IRImpl
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

suspend fun compileImpl(impl: IRImpl): ByteArray? {
    val cw = ClassWriter(0)

    cw.visit(
        62,
        Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
        impl.fullSignature.toJvmNotation(),
        null,
        "java/lang/Object",
        null
    )

    var isUsed = false

    impl.associatedFunctions.forEach { (name, func) ->
        func.checkedVariants().map { (argTypes, body) ->
            isUsed = true
            compileCheckedFunction(cw, name, body.first,body.second, argTypes)
        }
        func.keepBlocks.forEach { (name, type) ->
            cw.visitField(
                Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE,
                name,
                type.toJVMDescriptor(),
                null,
                null
            )
        }
    }

    impl.methods.forEach { (name, func) ->
        func.checkedVariants().map { (argTypes, body) ->
            isUsed = true
            compileCheckedFunction(cw, name, body.first,body.second, argTypes)
        }
        func.keepBlocks.forEach { (name, type) ->
            cw.visitField(
                Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE,
                name,
                type.toJVMDescriptor(),
                null,
                null
            )
        }

    }

    if (!isUsed) return null

    return cw.toByteArray()
}