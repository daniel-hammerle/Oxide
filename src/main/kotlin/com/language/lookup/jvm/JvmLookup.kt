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
package com.language.lookup.jvm

import com.language.compilation.FunctionCandidate
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.tracking.BroadForge
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.InstanceLookup
import com.language.compilation.tracking.JvmInstanceForge
import com.language.compilation.tracking.StructInstanceForge
import com.language.lookup.IRLookup
import com.language.lookup.jvm.parsing.FunctionInfo

interface JvmLookup {
    suspend fun lookUpMethod(
        instance: InstanceForge,
        functionName: String,
        argTypes: List<InstanceForge>,
        lookup: IRLookup
    ): FunctionCandidate?

    suspend fun lookUpMethodUnknown(
        instance: JvmInstanceForge,
        functionName: String,
        argTypes: List<BroadForge>,
        lookup: IRLookup
    ): BroadForge?

    suspend fun lookUpAssociatedFunction(
        className: SignatureString,
        functionName: String,
        argTypes: List<InstanceForge>,
        lookup: IRLookup,
        generics: Map<String, Type.Broad>
    ): FunctionCandidate?

    suspend fun lookUpAssociatedFunctionUnknown(
        className: SignatureString,
        functionName: String,
        argTypes: List<BroadForge>,
        lookup: IRLookup,
        generics: Map<String, Type.Broad>
    ): BroadForge?

    suspend fun lookUpField(instance: Type.JvmType, fieldName: String, lookup: IRLookup): Type?

    suspend fun lookUpAssociatedField(className: SignatureString, fieldName: String): Type?

    suspend fun lookupFieldForge(className: SignatureString, fieldName: String): InstanceForge?

    suspend fun hasGenericReturnType(
        instance: Type.JvmType,
        functionName: String,
        argTypes: List<Type>,
        lookup: IRLookup
    ): Boolean

    suspend fun typeHasInterface(type: Type.JvmType, interfaceType: SignatureString): Boolean

    suspend fun getModifiers(className: SignatureString): Modifiers

    suspend fun lookupConstructor(
        className: SignatureString,
        argTypes: List<InstanceForge>,
        lookup: IRLookup
    ): FunctionCandidate?

    suspend fun lookupConstructorUnknown(
        className: SignatureString,
        argTypes: List<BroadForge>,
        lookup: IRLookup
    ): BroadForge?

    suspend fun lookUpGenericTypes(
        instance: Type.JvmType,
        funcName: String,
        argTypes: List<Type>,
        lookup: IRLookup
    ): Map<String, Type>?

    suspend fun lookUpGenericsDefinitionOrder(className: SignatureString): List<String>

    suspend fun lookupErrorTypes(
        visited: MutableSet<FunctionInfo>,
        className: SignatureString,
        funcName: String,
        argTypes: List<Type>,
        lookup: IRLookup
    ): Set<SignatureString>

}