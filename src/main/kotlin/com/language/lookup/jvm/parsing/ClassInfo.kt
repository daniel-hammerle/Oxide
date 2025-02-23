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
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifiers

data class ClassInfo(
    val modifiers: Modifiers,
    val signature: SignatureString,
    val constructors: Set<FunctionInfo>,
    val generics: List<GenericTypeParameter>,
    val fields: Map<String, TemplatedType>,
    val interfaces: Set<SignatureString>,
    val staticFields: Map<String, Type>,
    val methods: Map<String, Set<FunctionInfo>>,
    val associatedFunctions: Map<String, Set<FunctionInfo>>,
)