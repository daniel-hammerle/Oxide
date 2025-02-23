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

package com.language.compilation.metadata

import com.language.compilation.*
import com.language.compilation.tracking.InstanceBuilder
import com.language.compilation.tracking.InstanceChange
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.join
import org.objectweb.asm.Label

class FunctionMetaDataHandle(
    override val inheritedGenerics: Map<String, Type>,
    private val appender: LambdaAppender,
    override val inlinableLambdas: List<TypedInstruction.Lambda>,
    override val returnLabel: Label?
) : MetaDataHandle {
    lateinit var returnType: InstanceForge
        private set

    var varCount: Int = 0

    fun hasReturnType() = ::returnType.isInitialized

    override fun issueReturnTypeAppend(type: InstanceForge) {
        returnType = if (::returnType.isInitialized) {
            returnType.join(type)
        } else {
            type
        }
    }

    val keepBlocks: MutableMap<String, Type> = mutableMapOf()

    override fun appendKeepBlock(name: String, type: Type) {
        keepBlocks[name] = type
    }

    override suspend fun addLambda(
        argNames: List<String>,
        captures: Map<String, InstanceForge>,
        body: Instruction,
        generics: Map<String, Type>,
        imports: Set<SignatureString>
    ): SignatureString {
        return appender.addLambda(argNames, captures, body, generics, imports)
    }

    fun toMetaData(returnBuilder: InstanceBuilder, args: List<InstanceChange>, uniqueId: Int) = FunctionMetaData(returnType.type, returnBuilder, args, uniqueId, varCount)
}