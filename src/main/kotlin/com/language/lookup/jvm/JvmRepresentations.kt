package com.language.lookup.jvm

import com.language.codegen.Box
import com.language.codegen.getOrThrow
import com.language.compilation.*
import com.language.lookup.IRLookup
import com.language.lookup.jvm.rep.asLazyTypeMap
import com.language.lookup.jvm.rep.orderByKeys
import org.objectweb.asm.Opcodes
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable

typealias ReflectModifiers = java.lang.reflect.Modifier
typealias ReflectType = java.lang.reflect.Type

fun Field.toType(generics: Map<String, Type.Broad>) = genericType.toType(generics) ?: type.toType()

fun ReflectType.toType(generics: Map<String, Type.Broad>) = when(val tp = generics[typeName]) {
    is Type.Broad.Known -> tp.type
    Type.Broad.Unset -> Type.Object
    null -> null
    is Type.Broad.UnknownUnionized -> TODO()
}

data class JvmStaticMethodRepresentation(
    private val ownerSignature: SignatureString,
    private val ownerIsInterface: Boolean,
    private val name: String,
    private val methods: Set<Method>,
    private val variants: Cache<List<Type>, FunctionCandidate>
) {
    suspend fun lookupVariant(argTypes: List<Type>, jvmLookup: JvmLookup, generics: Map<String, Type.Broad>, lookup: IRLookup): FunctionCandidate? {
        if (variants.contains(argTypes)) return variants.get(argTypes)

        val (vararg, method) = methods.firstNotNullOfOrNull {
            val result = it.fitsArgTypes(argTypes)
            Box(result.first).takeIf { result.second }?.let { a -> a to it }
        } ?: return null

        val oxideReturnType = evaluateReturnType(argTypes, generics, method, jvmLookup, lookup)
        val errorTypes = getErrorTypes(method)

        val actualReturnType = errorTypes.fold(oxideReturnType) { acc, it -> acc.join(Type.BasicJvmType(it)) }

        val jvmArgTypes = method.parameterTypes.map { it.toType() }
        val candidate =  SimpleFunctionCandidate(
            argTypes,
            jvmArgTypes,
            oxideReturnType,
            actualReturnType,
            Opcodes.INVOKESTATIC,
            ownerSignature,
            name,
            obfuscateName = false,
            requireDispatch = false,
            isInterface = ownerIsInterface,
            varargInfo = vararg.item,
            requiresCatch = errorTypes
        )

        variants.set(argTypes, candidate)
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
    private val variants: Cache<Pair<Map<String, Type.Broad>, List<Type>>, FunctionCandidate>,
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

    suspend fun lookupVariantUnknown(type: Type, generics: Map<String, Type.Broad>, argTypes: List<Type.Broad>, jvmLookup: JvmLookup, lookup: IRLookup): Type.Broad? {
        val result = methods.filter { it.fitsArgTypes(argTypes).second }

        if (result.isEmpty() || result.size > 1) return null

        val method = result.first()
        val oxideReturnType = evaluateReturnType(argTypes.populate(method), generics, method, jvmLookup, lookup)
        val errorTypes = getErrorTypes(method)

        val actualReturnType = errorTypes.fold(oxideReturnType) { acc, it -> acc.join(Type.BasicJvmType(it)) }

       return Type.Broad.Known(actualReturnType)
    }


    suspend fun lookupVariant(type: Type, generics: Map<String, Type.Broad>, argTypes: List<Type>, jvmLookup: JvmLookup, lookup: IRLookup): FunctionCandidate? {
        if (variants.contains(generics to argTypes)) return variants.get(generics to argTypes)

        val (vararg, method) = methods.firstNotNullOfOrNull {
            val result = it.fitsArgTypes(argTypes)
            Box(result.first).takeIf { result.second }?.let { a -> a to it }
        } ?: return null

        val oxideReturnType = evaluateReturnType(argTypes, generics, method, jvmLookup, lookup)

        val errorTypes = getErrorTypes(method)

        val actualReturnType = errorTypes.fold(oxideReturnType) { acc, it -> acc.join(Type.BasicJvmType(it)) }

        val jvmArgTypes = method.parameterTypes.map { it.toType() }
        val candidate =  SimpleFunctionCandidate(
            listOf(type) + argTypes,
            jvmArgTypes,
            method.returnType.toType(),
            actualReturnType,
            if (isOwnerInterface) Opcodes.INVOKEINTERFACE else Opcodes.INVOKEVIRTUAL,
            ownerSignature,
            name,
            obfuscateName = false,
            requireDispatch = false,
            castReturnType = hasGenericReturnType(argTypes),
            isInterface = ownerIsInterface,
            varargInfo = vararg.item,
            errorTypes
        )

        variants.set(generics to argTypes, candidate)
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
                    reflectTp.extract(oxideTp.getOrThrow("Invalid type"), lookup)
                }
                .reduce { acc, map -> acc + map }
        }
        is GenericArrayType -> {
            type as Type.Array
            val name = this.typeName.removeSuffix("[]")
            mapOf(name to type.itemType)
        }
        else -> emptyMap()
    }

}


