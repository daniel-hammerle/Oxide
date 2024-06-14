package com.language.lookup.oxide

import com.language.TemplatedType
import com.language.compilation.FunctionCandidate
import com.language.compilation.IRFunction
import com.language.lookup.IRLookup
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifiers

interface OxideLookup {
    fun newModFrame(modNames: Set<SignatureString>): OxideLookup
    suspend fun lookUpGenericTypes(instance: Type, funcName: String, argTypes: List<Type>): Map<String, Int>?
    suspend fun lookupFunction(module: SignatureString, funcName: String, args: List<Type>, lookup: IRLookup): FunctionCandidate

    suspend fun lookupExtensionMethod(instance: Type, funcName: String, args: List<Type>, lookup: IRLookup): FunctionCandidate
    suspend fun lookupAssociatedExtensionFunction(structName: SignatureString, funcName: String, args: List<Type>, lookup: IRLookup): FunctionCandidate

    suspend fun lookupMemberField(instance: Type, name: String, lookup: IRLookup): Type

    suspend fun lookupLambdaInit(signatureString: SignatureString): FunctionCandidate
    suspend fun lookupLambdaInvoke(signatureString: SignatureString, argTypes: List<Type>, lookup: IRLookup): FunctionCandidate

    suspend fun lookupConstructor(structName: SignatureString, args: List<Type>, lookup: IRLookup): FunctionCandidate
    suspend fun lookupModifiers(structName: Type): Modifiers

    fun lookupOrderedFields(structName: SignatureString): List<Pair<String, TemplatedType>>

    suspend fun lookupStructGenericModifiers(structSig: SignatureString): Map<String, Modifiers>

    suspend fun findExtensionFunction(instance: Type, funcName: String, lookup: IRLookup): IRFunction
    suspend fun findFunction(modName: SignatureString, funcName: String, lookup: IRLookup): IRFunction

}