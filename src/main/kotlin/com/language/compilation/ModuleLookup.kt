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
package com.language.compilation

import com.language.Function
import com.language.Module
import com.language.ModuleChild
import com.language.TemplatedType

interface ModuleLookup {
    fun localGetFunction(name: String): Function?
    fun localGetSymbol(name: String): ModuleChild?

    val localName: SignatureString
    val containerName: SignatureString
    val localImports: Map<String, SignatureString>
    val localSymbols: Map<String, ModuleChild>

    fun withNewContainer(newContainerName: SignatureString): ModuleLookup

    fun getImport(name: String): SignatureString?

    fun hasStruct(name: SignatureString): Boolean
    fun hasLocalStruct(name: SignatureString): Boolean
    fun hasModule(name: SignatureString): Boolean
    fun nativeModule(name: SignatureString): Module?
    fun hasType(name: SignatureString): Boolean
    fun unwindType(name: SignatureString, tp: TemplatedType.Complex): TemplatedType
}