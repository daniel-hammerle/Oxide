package com.language.lookup.jvm

import com.language.codegen.getOrDefault
import com.language.codegen.getOrThrow
import com.language.compilation.*
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.modifiers.modifiers
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

fun Field.toType(generics: Map<String, Type.BroadType>) = genericType.toType(generics) ?: type.toType()

fun ReflectType.toType(generics: Map<String, Type.BroadType>) = when(val tp = generics[typeName]) {
    is Type.BroadType.Known -> tp.type
    Type.BroadType.Unset -> Type.Object
    null -> null
}

data class JvmStaticMethodRepresentation(
    private val ownerSignature: SignatureString,
    private val name: String,
    private val methods: Set<Method>,
    private val variants: Cache<List<Type>, FunctionCandidate>
) {
    suspend fun lookupVariant(argTypes: List<Type>, jvmLookup: JvmLookup, generics: Map<String, Type.BroadType>, lookup: IRLookup): FunctionCandidate? {
        if (variants.contains(argTypes)) return variants.get(argTypes)

        val method = methods.firstOrNull { it.fitsArgTypes(argTypes) } ?: return null
        val oxideReturnType = evaluateReturnType(argTypes, generics, method, jvmLookup, lookup)

        val jvmArgTypes = method.parameterTypes.map { it.toType() }
        val candidate =  FunctionCandidate(
            argTypes,
            jvmArgTypes,
            oxideReturnType,
            oxideReturnType,
            Opcodes.INVOKESTATIC,
            ownerSignature,
            name,
            obfuscateName = false,
            requireDispatch = false
        )

        variants.set(argTypes, candidate)
        return candidate
    }
}

data class JvmMethodRepresentation(
    private val ownerSignature: SignatureString,
    private val name: String,
    private val methods: Set<Method>,
    private val variants: Cache<Pair<Map<String, Type.BroadType>, List<Type>>, FunctionCandidate>,
    private val isOwnerInterface: Boolean,
) {

    fun hasGenericReturnType(argTypes: List<Type>): Boolean =
        methods.first { it.fitsArgTypes(argTypes) }.let { it.genericReturnType.typeName != it.returnType.name }

    suspend fun lookupGenericTypes(argTypes: List<Type>, lookup: IRLookup): Map<String, Type>? {
        val method = methods.firstOrNull { it.fitsArgTypes(argTypes) } ?: return null
        val result = method.genericParameterTypes
            .zip(argTypes)
            .fold(emptyMap<String, Type>()) { acc, (reflectType, tp) -> acc + reflectType.extract(tp, lookup).asLazyTypeMap() }
        return result
    }

    suspend fun lookupVariant(type: Type, generics: Map<String, Type.BroadType>, argTypes: List<Type>, jvmLookup: JvmLookup, lookup: IRLookup): FunctionCandidate? {
        if (variants.contains(generics to argTypes)) return variants.get(generics to argTypes)

        val method = methods.firstOrNull { it.fitsArgTypes(argTypes) } ?: return null

        val oxideReturnType = evaluateReturnType(argTypes, generics, method, jvmLookup, lookup)

        val jvmArgTypes = method.parameterTypes.map { it.toType() }
        val candidate =  FunctionCandidate(
            listOf(type) + argTypes,
            jvmArgTypes,
            method.returnType.toType(),
            oxideReturnType,
            if (isOwnerInterface) Opcodes.INVOKEINTERFACE else Opcodes.INVOKEVIRTUAL,
            ownerSignature,
            name,
            obfuscateName = false,
            requireDispatch = false,
            castReturnType = hasGenericReturnType(argTypes)
        )

        variants.set(generics to argTypes, candidate)
        return candidate
    }
}

suspend fun ReflectType.extract(type: Type, lookup: IRLookup): Map<String, Type.BroadType> {
    return when(this) {
        is TypeVariable<*> -> mapOf(name to Type.BroadType.Known(type))
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


