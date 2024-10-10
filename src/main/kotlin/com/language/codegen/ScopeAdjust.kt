package com.language.codegen

import com.language.compilation.ScopeAdjustInstruction
import com.language.compilation.ScopeAdjustment
import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import org.objectweb.asm.MethodVisitor

fun compileScopeAdjustment(
    mv: MethodVisitor,
    adjustment: ScopeAdjustment,
    stackMap: StackMap
) {
    for (ins in adjustment.instructions) {
        compileScopeAdjustInstruction(mv, ins, stackMap)
    }
}

fun compileScopeAdjustInstruction(
    mv: MethodVisitor,
    ins: ScopeAdjustInstruction,
    stackMap: StackMap
) {
    when(ins) {
        is ScopeAdjustInstruction.Box -> {
            mv.visitVarInsn(loadInstruction(ins.type), ins.src)
            boxOrIgnore(mv, ins.type)
            mv.visitVarInsn(storeInstruction(ins.type), ins.src)
        }
        is ScopeAdjustInstruction.Move -> {
            mv.visitVarInsn(loadInstruction(ins.type), ins.src)
            mv.visitVarInsn(storeInstruction(ins.type), ins.dest)
        }
        is ScopeAdjustInstruction.Store -> {
            compileInstruction(mv, TypedInstruction.StoreVar(ins.dest, ins.value), stackMap)
        }
        is ScopeAdjustInstruction.Unbox -> {
            mv.visitVarInsn(loadInstruction(ins.type), ins.src)
            unboxOrIgnore(mv, ins.type, ins.type.asUnboxed())
            mv.visitVarInsn(storeInstruction(ins.type), ins.src)
        }
    }
}