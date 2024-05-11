package com.language.lookup.jvm

import com.language.compilation.FunctionCandidate
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifiers

interface JvmLookup {
    suspend fun lookUpMethod(instance: Type.JvmType, functionName: String, argTypes: List<Type>): FunctionCandidate?

    suspend fun lookUpAssociatedFunction(className: SignatureString, functionName: String, argTypes: List<Type>): FunctionCandidate?

    suspend fun lookUpField(instance: Type.JvmType, fieldName: String): Type?

    suspend fun lookUpAssociatedField(className: SignatureString, fieldName: String): Type?

    suspend fun hasGenericReturnType(instance: Type.JvmType, functionName: String, argTypes: List<Type>): Boolean

    suspend fun typeHasInterface(type: Type.JvmType, interfaceType: SignatureString): Boolean

    suspend fun getModifiers(className: SignatureString): Modifiers

    suspend fun lookupConstructor(className: SignatureString, argTypes: List<Type>): FunctionCandidate?

}