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