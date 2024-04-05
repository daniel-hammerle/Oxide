package com.language.codegen

import com.language.compilation.IRFunction
import com.language.compilation.IRModuleLookup
import com.language.compilation.Type
import com.language.compilation.VariableMappingImpl
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

fun compileCheckedFunction(cw: ClassWriter, function: IRFunction, name: String, lookup: IRModuleLookup, argTypes: List<Type>) {
    val returnType = function.type(argTypes, lookup)
    val mv = cw.visitMethod(
        Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC,
        name,
        generateJVMFunctionSignature(argTypes, returnType),
        null,
        arrayOf()
    )
    val variables = VariableMappingImpl.fromVariables(function.args.zip(argTypes).associate { (arg, type) -> arg to type }.toMap())
    mv.visitMaxs(20, function.getVarCount(argTypes, lookup)) // 1 for the instance

    //compile body
    compileInstruction(mv, function.body, variables, lookup)

    //return
    when(returnType) {
        is Type.JvmType, Type.Null, is Type.Union -> mv.visitInsn(Opcodes.ARETURN)
        Type.IntT, Type.BoolT -> mv.visitInsn(Opcodes.IRETURN)
        Type.DoubleT -> mv.visitInsn(Opcodes.DRETURN)
        Type.Nothing -> mv.visitInsn(Opcodes.RETURN)
    }
}