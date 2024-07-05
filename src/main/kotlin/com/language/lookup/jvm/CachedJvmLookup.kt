package com.language.lookup.jvm

import com.language.compilation.FunctionCandidate
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifiers
import com.language.lookup.IRLookup
import com.language.lookup.jvm.parsing.ClassParser
import com.language.lookup.jvm.rep.BasicJvmClassRepresentation
import com.language.lookup.jvm.rep.JvmClassInfoRepresentation
import com.language.lookup.jvm.rep.JvmClassRepresentation
import org.objectweb.asm.ClassReader

class CachedJvmLookup(
    private val loader: RawClassLoader
) : JvmLookup {
    private val classCache: Cache<SignatureString, JvmClassRepresentation> = Cache()

    private fun createRep(signatureString: SignatureString): JvmClassRepresentation {
        val classReader = loader.getReader(signatureString.toDotNotation())

        return when (classReader) {
            is ClassReader -> {
                val info = ClassParser(classReader, signatureString).toClassInfo()
                JvmClassInfoRepresentation(info)
            }
            else ->  BasicJvmClassRepresentation(loader.loadClass(signatureString.toDotNotation()))
        }

    }

    private suspend fun getClass(signatureString: SignatureString) =
        classCache.get(signatureString) ?: createRep(signatureString).also {
            classCache.set(signatureString, it)
        }

    override suspend fun lookUpMethod(instance: Type.JvmType, functionName: String, argTypes: List<Type>, lookup: IRLookup): FunctionCandidate? {
        return getClass(instance.signature).lookupMethod(functionName, instance, instance.genericTypes, argTypes, lookup, this)
    }

    override suspend fun lookUpAssociatedFunction(
        className: SignatureString,
        functionName: String,
        argTypes: List<Type>,
        lookup: IRLookup,
        generics: Map<String, Type.BroadType>
    ): FunctionCandidate? = getClass(className).lookUpAssociatedFunction(functionName, argTypes, lookup, this, generics)

    override suspend fun lookUpField(instance: Type.JvmType, fieldName: String, lookup: IRLookup): Type? {
        return getClass(instance.signature).lookUpField(fieldName, instance.genericTypes, lookup)
    }

    override suspend fun lookUpAssociatedField(className: SignatureString, fieldName: String): Type? {
        return getClass(className).lookUpStaticField(fieldName)
    }

    override suspend fun hasGenericReturnType(
        instance: Type.JvmType,
        functionName: String,
        argTypes: List<Type>,
        lookup: IRLookup
    ): Boolean = getClass(instance.signature).methodHasGenericReturnType(functionName, argTypes, lookup)

    override suspend fun typeHasInterface(type: Type.JvmType, interfaceType: SignatureString): Boolean {
        return getClass(type.signature).hasInterface(interfaceType)
    }

    override suspend fun getModifiers(className: SignatureString): Modifiers {
        return getClass(className).modifiers
    }

    override suspend fun lookupConstructor(className: SignatureString, argTypes: List<Type>, lookup: IRLookup): FunctionCandidate? {
        return getClass(className).lookupConstructor(argTypes, lookup)
    }

    override suspend fun lookUpGenericTypes(instance: Type.JvmType, funcName: String, argTypes: List<Type>, lookup: IRLookup): Map<String, Type>? {
        return getClass(instance.signature).lookupGenericTypes(funcName, argTypes, lookup)
    }

    override suspend fun lookUpGenericsDefinitionOrder(className: SignatureString): List<String> {
        return getClass(className).getGenericDefinitionOrder()
    }


}