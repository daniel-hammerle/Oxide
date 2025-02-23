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
package com.language.lookup.jvm.parsing

import com.language.TemplatedType
import com.language.compilation.SignatureString

data class FunctionInfo(
    val name: String,
    val owner: SignatureString,
    val genericDefinitions: List<GenericTypeParameter>,
    val args: List<TemplatedType>,
    val returnType: TemplatedType,
    val annotations: Set<AnnotationInfo>,
    val exceptionInfo: ExceptionInfo
)

data class ExceptionInfo(val exceptions: Set<SignatureString>, val others: Set<FunctionMention>)

data class FunctionMention(val owner: SignatureString, val name: String, val argTypes: List<TemplatedType>)

data class GenericTypeParameter(val name: String, val bounds: List<SignatureString>)
