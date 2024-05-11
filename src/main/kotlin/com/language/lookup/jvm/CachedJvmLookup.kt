package com.language.lookup.jvm

import com.language.compilation.FunctionCandidate
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifiers

class CachedJvmLookup(
    private val loader: ClassLoader
) : JvmLookup {
    private val classCache: Cache<SignatureString, JvmClassRepresentation> = Cache()

    private suspend fun getClass(signatureString: SignatureString) =
        classCache.get(signatureString) ?: JvmClassRepresentation(loader.loadClass(signatureString.toDotNotation())).also {
            classCache.set(signatureString, it)
        }

    override suspend fun lookUpMethod(instance: Type.JvmType, functionName: String, argTypes: List<Type>): FunctionCandidate? {
        return getClass(instance.signature).lookupMethod(functionName, instance.genericTypes, argTypes)
    }

    override suspend fun lookUpAssociatedFunction(
        className: SignatureString,
        functionName: String,
        argTypes: List<Type>
    ): FunctionCandidate? = getClass(className).lookUpAssociatedFunction(functionName, argTypes)

    override suspend fun lookUpField(instance: Type.JvmType, fieldName: String): Type? {
        return getClass(instance.signature).lookUpField(fieldName, instance.genericTypes)
    }

    override suspend fun lookUpAssociatedField(className: SignatureString, fieldName: String): Type? {
        return getClass(className).lookUpStaticField(fieldName)
    }

    override suspend fun hasGenericReturnType(
        instance: Type.JvmType,
        functionName: String,
        argTypes: List<Type>
    ): Boolean = getClass(instance.signature).methodHasGenericReturnType(functionName, argTypes)

    override suspend fun typeHasInterface(type: Type.JvmType, interfaceType: SignatureString): Boolean {
        return getClass(type.signature).hasInterface(interfaceType)
    }

    override suspend fun getModifiers(className: SignatureString): Modifiers {
        return getClass(className).modifiers
    }

    override suspend fun lookupConstructor(className: SignatureString, argTypes: List<Type>): FunctionCandidate? {
        return getClass(className).lookupConstructor(argTypes)
    }


}