package com.language.lookup.jvm

import com.language.codegen.Box
import com.language.codegen.getOrThrow
import com.language.compilation.*
import com.language.compilation.tracking.*
import com.language.lookup.IRLookup
import com.language.lookup.jvm.rep.asLazyTypeMap
import com.language.lookup.jvm.rep.fromTypeAsJvm
import com.language.lookup.jvm.rep.orderByKeys
import org.objectweb.asm.Opcodes
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable

typealias ReflectModifiers = java.lang.reflect.Modifier
typealias ReflectType = java.lang.reflect.Type

fun Field.toForge(generics: Map<String, Type>) = genericType.toForge(generics) ?: type.toForge()

fun ReflectType.toForge(generics: Map<String, Type>) = when(val tp = generics[typeName]) {
    Type.UninitializedGeneric -> Type.Object
    null-> null
    else -> tp

}

data class JvmStaticMethodRepresentation(
    private val ownerSignature: SignatureString,
    private val ownerIsInterface: Boolean,
    private val name: String,
    private val methods: Set<Method>,
    private val variants: Cache<List<Type>, FunctionCandidate>
) {
    suspend fun lookupVariant(argTypes: List<InstanceForge>, jvmLookup: JvmLookup, generics: Map<String, Type.Broad>, lookup: IRLookup): FunctionCandidate? {
        val tps = argTypes.map { it.type  }
        if (variants.contains(tps)) return variants.get(tps)

        val (vararg, method) = methods.firstNotNullOfOrNull {
            val result = it.fitsArgTypes(tps)
            Box(result.first).takeIf { result.second }?.let { a -> a to it }
        } ?: return null

        //get generic changes if any part of the argument is generic any value passed into this is now part of the generic.
        val genericAppends = genericAppends(method.genericParameterTypes.toList(), argTypes, lookup)

        val oxideReturnType = evaluateReturnType(argTypes, genericAppends, method, jvmLookup, lookup) as InstanceForge

        val errorTypes = getErrorTypes(method)

        val actualReturnType = errorTypes.fold(oxideReturnType) { acc, it -> acc.join(JvmInstanceForge(mutableMapOf(), it)) }



        val jvmArgTypes = method.parameterTypes.map { it.toForge() }
        val candidate =  SimpleFunctionCandidate(
            tps,
            jvmArgTypes,
            oxideReturnType.type,
            actualReturnType.type,
            Opcodes.INVOKESTATIC,
            ownerSignature,
            name,
            name,
            obfuscateName = false,
            requireDispatch = false,
            isInterface = ownerIsInterface,
            varargInfo = vararg.item,
            returnForge = actualReturnType,
            requiresCatch = errorTypes
        )

        variants.set(tps, candidate)
        return candidate
    }
    fun getErrorTypes(argTypes: List<Type>): Set<SignatureString> {
        val (_, method) = methods.firstNotNullOfOrNull {
            val result = it.fitsArgTypes(argTypes)
            Box(result.first).takeIf { result.second }?.let { a -> a to it }
        } ?:  error("No method found $argTypes")

        return getErrorTypes(method)
    }

    private fun getErrorTypes(method: Method): Set<SignatureString> {
        val exceptions = method.exceptionTypes.map { SignatureString.fromDotNotation(it.name) }

        return exceptions.toSet()
    }
}

data class JvmMethodRepresentation(
    private val ownerSignature: SignatureString,
    private val ownerIsInterface: Boolean,
    private val name: String,
    private val methods: Set<Method>,
    private val variants: Cache<Pair<Map<String, Type>, List<Type>>, FunctionCandidate>,
    private val isOwnerInterface: Boolean,
) {

    fun hasGenericReturnType(argTypes: List<Type>): Boolean =
        methods.first { it.fitsArgTypes(argTypes).second }.let { it.genericReturnType.typeName != it.returnType.name }

    suspend fun lookupGenericTypes(argTypes: List<Type>, lookup: IRLookup): Map<String, Type>? {
        val method = methods.firstOrNull { it.fitsArgTypes(argTypes).second } ?: return null
        val result = method.genericParameterTypes
            .zip(argTypes)
            .fold(emptyMap<String, Type>()) { acc, (reflectType, tp) -> acc + reflectType.extract(tp, lookup).asLazyTypeMap() }
        return result
    }

    suspend fun lookupVariantUnknown(type: Type, generics: Map<String, Type>, argTypes: List<BroadForge>, jvmLookup: JvmLookup, lookup: IRLookup): BroadForge? {
        val result = methods.filter { it.fitsArgTypes(argTypes.map { it.toBroadType() }).second }

        if (result.isEmpty()) return null

        if (argTypes.any { it !is InstanceForge } && result.size > 1) return null

        val method = result.first()
        //get generic changes if any part of the argument is generic any value passedi nto this is now part of the generic.
        val genericAppends = genericAppends(method.genericParameterTypes.toList(), argTypes, lookup)

        //safeguard since primitives are also jvm method clients but are not jvm instance forges
        if (genericAppends.isNotEmpty()) {
            type as JvmInstanceForge
            type.applyChanges(genericAppends)
        }
        val oxideReturnType = evaluateReturnType(argTypes, genericAppends, method, jvmLookup, lookup)

        val errorTypes = getErrorTypes(method)

        val actualReturnType = errorTypes.fold(oxideReturnType) { acc, it -> acc.join(JvmInstanceForge(mutableMapOf(), it)) }


        return actualReturnType
    }

        //Current
    suspend fun lookupVariant(type: InstanceForge, generics: Map<String, Type>, argTypes: List<InstanceForge>, jvmLookup: JvmLookup, lookup: IRLookup): FunctionCandidate? {
        val tps = argTypes.map { forge -> forge.type  }
        if (variants.contains(generics to tps)) return variants.get(generics to tps)

        val (vararg, method) = methods.firstNotNullOfOrNull {
            val result = it.fitsArgTypes(tps)
            Box(result.first).takeIf { result.second }?.let { a -> a to it }
        } ?: return null



        //get generic changes if any part of the argument is generic any value passedi nto this is now part of the generic.
        val genericAppends = genericAppends(method.genericParameterTypes.toList(), argTypes, lookup).toMutableMap()
        //safeguard since primitives are also jvm method clients but are not jvm instance forges
        if (genericAppends.isNotEmpty()) {
            type as JvmInstanceForge
            type.applyChanges(genericAppends)
        }

        if (type is JvmInstanceForge) {
            genericAppends +=type.generics.filter {it.value is InstanceForge  } as Map<String, InstanceForge>
        }
        val oxideReturnType = evaluateReturnType(argTypes,  genericAppends, method, jvmLookup, lookup)  as InstanceForge

        val errorTypes = getErrorTypes(method)

        val actualReturnType = errorTypes.fold(oxideReturnType) { acc, it -> acc.join(JvmInstanceForge(mutableMapOf(), it)) }


        val jvmArgTypes = method.parameterTypes.map { it.toForge() }
        val candidate =  SimpleFunctionCandidate(
            listOf(type.type) + tps,
            jvmArgTypes,
            method.returnType.toForge(),
            actualReturnType.type,
            if (isOwnerInterface) Opcodes.INVOKEINTERFACE else Opcodes.INVOKEVIRTUAL,
            ownerSignature,
            name,
            name,
            obfuscateName = false,
            requireDispatch = false,
            castReturnType = hasGenericReturnType(tps),
            isInterface = ownerIsInterface,
            varargInfo = vararg.item,
            errorTypes,
            returnForge = actualReturnType,
        )

        variants.set(generics to tps, candidate)
        return candidate
    }

    fun getErrorTypes(argTypes: List<Type>): Set<SignatureString> {
        val (_, method) = methods.firstNotNullOfOrNull {
            val result = it.fitsArgTypes(argTypes)
            Box(result.first).takeIf { result.second }?.let { a -> a to it }
        } ?: error("No method found")

        return getErrorTypes(method)
    }

    private fun getErrorTypes(method: Method): Set<SignatureString> {
        val exceptions = method.exceptionTypes.map { SignatureString.fromDotNotation(it.name) }

        return exceptions.toSet()
    }
}


suspend fun ReflectType.extract(type: Type, lookup: IRLookup): Map<String, Type.Broad> {
    return when(this) {
        is TypeVariable<*> -> mapOf(name to Type.Broad.Known(type))
        is ParameterizedType -> {
            type as Type.JvmType
            val orderedGenerics = type.genericTypes.orderByKeys(
                lookup.lookupOrderGenerics(SignatureString.fromDotNotation(typeName))
            )
            orderedGenerics
                .zip(actualTypeArguments)
                .map { (tp, reflectTp) ->
                    val (_, oxideTp) = tp
                    reflectTp.extract(oxideTp, lookup)
                }
                .reduce { acc, map -> acc + map }
        }
        is GenericArrayType -> {
            type as Type.Array
            val name = this.typeName.removeSuffix("[]")
            mapOf(name to Type.Broad.Known(type.itemType))
        }
        else -> emptyMap()
    }

}


