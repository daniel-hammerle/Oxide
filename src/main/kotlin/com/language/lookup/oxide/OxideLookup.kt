package com.language.lookup.oxide

import com.language.TemplatedType
import com.language.compilation.*
import com.language.lookup.IRLookup
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.tracking.BroadForge
import com.language.compilation.tracking.InstanceForge

interface OxideLookup {
    fun newModFrame(modNames: Set<SignatureString>): OxideLookup
    suspend fun lookUpGenericTypes(
        instance: Type,
        funcName: String,
        argTypes: List<Type>,
        lookup: IRLookup
    ): Map<String, Type>?

    suspend fun lookupFunction(
        module: SignatureString,
        funcName: String,
        args: List<InstanceForge>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate?

    suspend fun lookupFunctionUnknown(
        module: SignatureString,
        funcName: String,
        args: List<BroadForge>,
        lookup: IRLookup,
        history: History
    ): BroadForge

    suspend fun lookupExtensionMethod(
        instance: InstanceForge,
        funcName: String,
        args: List<InstanceForge>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate?

    suspend fun lookupExtensionMethodUnknown(
        instance: InstanceForge,
        funcName: String,
        args: List<BroadForge>,
        lookup: IRLookup,
        history: History
    ): BroadForge

    suspend fun lookupAssociatedExtensionFunction(
        structName: SignatureString,
        funcName: String,
        args: List<InstanceForge>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate

    suspend fun lookupAssociatedExtensionFunctionUnknown(
        structName: SignatureString,
        funcName: String,
        args: List<BroadForge>,
        lookup: IRLookup,
        history: History
    ): BroadForge

    suspend fun lookupMemberField(instance: Type, name: String, lookup: IRLookup): Type
    suspend fun lookupPhysicalField(instance: Type, name: String, lookup: IRLookup): Type

    suspend fun lookupLambdaInit(signatureString: SignatureString): FunctionCandidate
    suspend fun lookupLambdaInvoke(
        signatureString: SignatureString,
        argTypes: List<InstanceForge>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate

    suspend fun lookupConstructor(structName: SignatureString, args: List<InstanceForge>, lookup: IRLookup): FunctionCandidate?
    suspend fun lookupConstructorUnknown(structName: SignatureString, args: List<BroadForge>, lookup: IRLookup): BroadForge
    suspend fun lookupModifiers(structName: Type): Modifiers

    fun lookupOrderedFields(structName: SignatureString): List<Pair<String, TemplatedType>>

    suspend fun lookupStructGenericModifiers(structSig: SignatureString): Map<String, Modifiers>

    suspend fun findExtensionFunction(
        instance: Type,
        funcName: String,
        lookup: IRLookup
    ): Pair<IRFunction, Map<String, Type>>

    suspend fun findFunction(modName: SignatureString, funcName: String, lookup: IRLookup): IRFunction

}