package com.language.codegen
// Copyright 2025 Daniel Hammerle
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import com.language.compilation.ScopeAdjustInstruction
import com.language.compilation.ScopeAdjustment
import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import com.language.compilation.isBoolean
import com.language.compilation.isBoxedPrimitive
import com.language.compilation.isUnboxedPrimitive
import org.objectweb.asm.MethodVisitor

fun compileScopeAdjustment(
    mv: MethodVisitor,
    adjustment: ScopeAdjustment,
    stackMap: StackMap,
    clean: CleanupStack
) {
    for (ins in adjustment.instructions) {
        compileScopeAdjustInstruction(mv, ins, stackMap, clean)
    }
}

fun compileScopeAdjustInstruction(
    mv: MethodVisitor,
    ins: ScopeAdjustInstruction,
    stackMap: StackMap,
    clean: CleanupStack
) {
    when(ins) {
        is ScopeAdjustInstruction.Box -> {
            if (!ins.type.isUnboxedPrimitive()) return
            mv.visitVarInsn(loadInstruction(ins.type), ins.src)
            boxOrIgnore(mv, ins.type)
            mv.visitVarInsn(storeInstruction(ins.type), ins.src)
        }
        is ScopeAdjustInstruction.Move -> {
            if (ins.src == ins.dest) return
            mv.visitVarInsn(loadInstruction(ins.type), ins.src)
            mv.visitVarInsn(storeInstruction(ins.type), ins.dest)
        }
        is ScopeAdjustInstruction.Store -> {
            compileInstruction(mv, TypedInstruction.StoreVar(ins.dest, ins.value), stackMap, clean)
        }
        is ScopeAdjustInstruction.Unbox -> {
            if (!ins.type.isBoxedPrimitive()) return
            mv.visitVarInsn(loadInstruction(ins.type), ins.src)
            unboxOrIgnore(mv, ins.type, ins.type.asUnboxed())
            mv.visitVarInsn(storeInstruction(ins.type), ins.src)
        }
    }
}