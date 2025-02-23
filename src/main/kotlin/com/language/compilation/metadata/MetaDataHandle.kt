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
import com.language.compilation.tracking.InstanceForge
import org.objectweb.asm.Label

interface MetaDataHandle : LambdaAppender{
    fun issueReturnTypeAppend(type: InstanceForge)

    fun appendKeepBlock(name: String, type: Type)

    val inlinableLambdas: List<TypedInstruction.Lambda>

    val inheritedGenerics: Map<String, Type>

    val returnLabel: Label?
}


interface MetaDataTypeHandle {
    fun returnTypeAppend(type: Type.Broad)
    val inheritedGenerics: Map<String, Type>

}

class MetaDataTypeHandleImpl(type: Type.Broad? = null, override val inheritedGenerics: Map<String, Type>): MetaDataTypeHandle {
    var type: Type.Broad? = type
        private set

    override fun returnTypeAppend(type: Type.Broad) {
        this.type = this.type?.join(type) ?: type
    }
}