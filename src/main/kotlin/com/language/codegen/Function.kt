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

package com.language.codegen

import com.language.compilation.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

fun compileCheckedFunction(
    cw: ClassWriter,
    name: String,
    body: TypedInstruction,
    metaData: FunctionMetaData,
    argTypes: List<Type>,
    static: Boolean = true,
    instanceType: Type? = null
) {
    val accessModifiers = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or if (static) Opcodes.ACC_STATIC else 0
    val mv = cw.visitMethod(
        accessModifiers,
        "${name}_${metaData.uniqueId}",
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
    val outerCleanup = CleanUpFrame(metaData.returnType) {}
    compileInstruction(mv, body, stackMap, CleanupStack(mutableListOf(outerCleanup)))

    when(body.type) {
        Type.Nothing, Type.Never -> assert(stackMap.stackSize == 0) { "Expected 0 but was ${stackMap.stackSize} ${body.type} $name" }
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


fun Type.Broad.getOrDefault(type: Type): Type = when(this) {
    is Type.Broad.Typeful -> this.type
    Type.Broad.Unset -> type
}

fun Type.Broad.getOrNull(): Type? = when(this) {
    is Type.Broad.Typeful -> this.type
    Type.Broad.Unset -> null
}


fun Type.Broad.getOrThrow(message: String) =when(this) {
    is Type.Broad.Typeful -> this.type
    Type.Broad.Unset -> error(message)
}
