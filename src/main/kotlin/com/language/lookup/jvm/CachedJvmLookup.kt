package com.language.lookup.jvm

import com.language.compilation.FunctionCandidate
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.Type.JvmType
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.tracking.BroadForge
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.JvmInstanceForge
import com.language.compilation.tracking.StructInstanceForge
import com.language.lookup.IRLookup
import com.language.lookup.jvm.parsing.ClassParser
import com.language.lookup.jvm.parsing.FunctionInfo
import com.language.lookup.jvm.rep.BasicJvmClassRepresentation
import com.language.lookup.jvm.rep.JvmClassInfoRepresentation
import com.language.lookup.jvm.rep.JvmClassRepresentation
import org.objectweb.asm.ClassReader

class CachedJvmLookup(
    private val loader: RawClassLoader
) : JvmLookup {
    private val classCache: Cache<SignatureString, JvmClassRepresentation> = Cache()

    private fun createRep(signatureString: SignatureString): JvmClassRepresentation {
        return when (val classReader = loader.getReader(signatureString.toDotNotation())) {
            is ClassReader -> {
                val info = ClassParser(classReader, signatureString).toClassInfo()
                JvmClassInfoRepresentation(info)
            }

            else -> BasicJvmClassRepresentation(loader.loadClass(signatureString.toDotNotation()))
        }

    }

    private suspend fun getClass(signatureString: SignatureString) =
        classCache.get(signatureString) ?: try { createRep(signatureString).also {
            classCache.set(signatureString, it)
        }
        } catch (_: ClassNotFoundException) { null }
    override suspend fun lookUpMethod(
        instance: InstanceForge,
        functionName: String,
        argTypes: List<InstanceForge>,
        lookup: IRLookup
    ): FunctionCandidate? {
        val tp = (instance.type as JvmType)
        return getClass(tp.signature)?.lookupMethod(
            functionName,
            instance,
            tp.genericTypes,
            argTypes,
            lookup,
            this
        )
    }

    override suspend fun lookUpMethodUnknown(
        instance: JvmInstanceForge,
        functionName: String,
        argTypes: List<BroadForge>,
        lookup: IRLookup
    ): BroadForge? {
        return getClass(instance.fullSignature)?.lookupMethodUnknown(functionName, instance.type, instance.genericTypes, argTypes, lookup, this)
    }

    override suspend fun lookUpAssociatedFunction(
        className: SignatureString,
        functionName: String,
        argTypes: List<InstanceForge>,
        lookup: IRLookup,
        generics: Map<String, Type.Broad>
    ): FunctionCandidate? = getClass(className)?.lookUpAssociatedFunction(functionName, argTypes, lookup, this, generics)

    override suspend fun lookUpAssociatedFunctionUnknown(
        className: SignatureString,
        functionName: String,
        argTypes: List<BroadForge>,
        lookup: IRLookup,
        generics: Map<String, Type.Broad>
    ): BroadForge? {
        return getClass(className)?.lookUpAssociatedFunctionUnknown(functionName, argTypes, lookup, this, generics)
    }

    override suspend fun lookUpField(instance: JvmType, fieldName: String, lookup: IRLookup): Type? {
        return getClass(instance.signature)?.lookUpField(fieldName, instance.genericTypes, lookup)
    }

    override suspend fun lookUpAssociatedField(className: SignatureString, fieldName: String): Type? {
        return getClass(className)?.lookUpStaticField(fieldName)
    }

    override suspend fun lookupFieldForge(
        className: SignatureString,
        fieldName: String
    ): InstanceForge? {
        return getClass(className)?.lookupFieldForge(fieldName)
    }

    override suspend fun hasGenericReturnType(
        instance: Type.JvmType,
        functionName: String,
        argTypes: List<Type>,
        lookup: IRLookup
    ): Boolean = getClass(instance.signature)?.methodHasGenericReturnType(functionName, argTypes, lookup) ?: false

    override suspend fun typeHasInterface(type: Type.JvmType, interfaceType: SignatureString): Boolean {
        return runCatching { getClass(type.signature) }.getOrNull()?.hasInterface(interfaceType) == true
    }

    override suspend fun getModifiers(className: SignatureString): Modifiers {
        return getClass(className)?.modifiers!!
    }

    override suspend fun lookupConstructor(
        className: SignatureString,
        argTypes: List<InstanceForge>,
        lookup: IRLookup
    ): FunctionCandidate? {
        return getClass(className)?.lookupConstructor(argTypes, lookup)
    }

    override suspend fun lookupConstructorUnknown(
        className: SignatureString,
        argTypes: List<BroadForge>,
        lookup: IRLookup
    ): BroadForge? {
        return getClass(className)?.lookupConstructorUnknown(argTypes, lookup)
    }

    override suspend fun lookUpGenericTypes(
        instance: Type.JvmType,
        funcName: String,
        argTypes: List<Type>,
        lookup: IRLookup
    ): Map<String, Type>? {
        return getClass(instance.signature)?.lookupGenericTypes(funcName, argTypes, lookup)
    }

    override suspend fun lookUpGenericsDefinitionOrder(className: SignatureString): List<String> {
        return getClass(className)?.getGenericDefinitionOrder()!!
    }

    override suspend fun lookupErrorTypes(
        visited: MutableSet<FunctionInfo>,
        className: SignatureString,
        funcName: String,
        argTypes: List<Type>,
        lookup: IRLookup
    ): Set<SignatureString> {
        val clazz = getClass(className)
        if (funcName == "<init>") {
            //handle constructor
        }
        return runCatching { clazz!!.getErrorTypesAssociatedFunction(visited, funcName, argTypes, lookup, this) }.getOrNull()
            ?: clazz!!.getErrorTypesMethod(visited, funcName, argTypes, lookup, this)
    }


}