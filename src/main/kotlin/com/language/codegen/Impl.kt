package com.language.codegen

import com.language.compilation.IRImpl
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

suspend fun compileImpl(impl: IRImpl): ByteArray {
    val cw = ClassWriter(0)

    cw.visit(
        62,
        Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
        impl.fullSignature.toJvmNotation(),
        null,
        "java/lang/Object",
        null
    )

    impl.associatedFunctions.forEach { (name, func) ->
        func.checkedVariantsUniqueJvm().map { (argTypes, body) ->
            compileCheckedFunction(cw, name, body.first,body.second, argTypes)
        }
    }

    impl.methods.forEach { (name, func) ->
        func.checkedVariantsUniqueJvm().map { (argTypes, body) ->
            compileCheckedFunction(cw, name, body.first,body.second, argTypes)
        }
    }

    return cw.toByteArray()
}