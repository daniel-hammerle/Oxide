package com.language.lookup.oxide

import com.language.TemplatedType
import com.language.compilation.FunctionCandidate
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifiers

interface OxideLookup {
    suspend fun lookupFunction(module: SignatureString, funcName: String, args: List<Type>): FunctionCandidate

    suspend fun lookupExtensionMethod(instance: Type, funcName: String, args: List<Type>): FunctionCandidate
    suspend fun lookupAssociatedExtensionFunction(structName: SignatureString, funcName: String, args: List<Type>): FunctionCandidate

    suspend fun lookupMemberField(instance: Type, name: String): Type

    suspend fun lookupConstructor(structName: SignatureString, args: List<Type>): FunctionCandidate?
    suspend fun lookupModifiers(structName: Type): Modifiers
    fun lookupOrderedFields(structName: SignatureString): List<Pair<String, TemplatedType>>

    suspend fun lookupStructGenericModifiers(structSig: SignatureString): Map<String, Modifiers>
}