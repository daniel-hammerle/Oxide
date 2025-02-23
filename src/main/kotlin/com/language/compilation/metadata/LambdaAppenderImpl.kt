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

import com.language.compilation.IRModule
import com.language.compilation.Instruction
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.tracking.InstanceForge

class LambdaAppenderImpl(
    var module: IRModule?
): LambdaAppender {
    override suspend fun addLambda(
        argNames: List<String>,
        captures: Map<String, InstanceForge>,
        body: Instruction,
        generics: Map<String, Type>,
        imports: Set<SignatureString>
    ): SignatureString {
        return module?.addLambda(argNames, captures, body, generics, imports)!!
    }

}