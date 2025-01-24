package com.language.lookup.jvm

import com.language.compilation.FunctionCandidate
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.InstanceLookup
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
        instance: Type.JvmType,
        functionName: String,
        argTypes: List<Type.Broad>,
        lookup: IRLookup
    ): Type.Broad?

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
        argTypes: List<Type.Broad>,
        lookup: IRLookup,
        generics: Map<String, Type.Broad>
    ): Type.Broad?

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
        argTypes: List<Type.Broad>,
        lookup: IRLookup
    ): Type.Broad?

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