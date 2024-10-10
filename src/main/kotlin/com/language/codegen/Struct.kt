package com.language.codegen

import com.language.compilation.IRStruct
import com.language.compilation.SignatureString
import com.language.compilation.Type
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

fun compileStruct(modName: SignatureString, structName: String, struct: IRStruct): ByteArray? {
    val cw = ClassWriter(0)
    val structJVMName = modName.toJvmNotation() + "/$structName"
    cw.visit(
        49,
        Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
        structJVMName,
        null,
        "java/lang/Object",
        null
    )
    //return null if the struct is unused, meaning no variant was generated
    if (struct.defaultVariant == null) {
        return null
    }
    //create fields
    for ((name, type) in struct.defaultVariant!!) {
        cw.visitField(
            Opcodes.ACC_PUBLIC,
            name,
            type.toJVMDescriptor(),
            null,
            null
        )
    }
    //create default constructor
    generateConstructor(cw, struct.defaultVariant!!, modName+SignatureString(structName))
    return cw.toByteArray()


}

fun generateConstructor(cw: ClassWriter, fields: Map<String, Type>, signatureString: SignatureString) {
    val mv = cw.visitMethod(
        Opcodes.ACC_PUBLIC,
        "<init>",
        generateJVMFunctionSignature(fields.values, Type.Nothing),
        null,
        null

    )
    mv.visitMaxs(3, 1+fields.map { it.value.size }.sum())
    mv.visitVarInsn(Opcodes.ALOAD, 0); // Load "this" onto the stack
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); // Invoke super constructor

    var i = 0
    for ((name, type) in fields) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        val loadingInstruction = when(type) {
            is Type.IntT, is Type.BoolT -> Opcodes.ILOAD
            is Type.DoubleT -> Opcodes.DLOAD
            is Type.Null -> Opcodes.ALOAD
            is Type.Nothing -> error("Nothing has no type")
            else -> Opcodes.ALOAD
        }

        mv.visitVarInsn(loadingInstruction, i+1)
        mv.visitFieldInsn(Opcodes.PUTFIELD, signatureString.toJvmNotation(), name, type.toJVMDescriptor())
        i+= when(type) {
            is Type.DoubleT -> 2
            else -> 1
        }
    }
    mv.visitInsn(Opcodes.RETURN)
}