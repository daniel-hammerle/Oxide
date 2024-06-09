package com.language.lookup.jvm

import com.language.compilation.*
import com.language.lookup.jvm.contract.ContractItem
import com.language.lookup.jvm.contract.matches
import com.language.lookup.jvm.contract.parseContractString
import com.language.lookup.jvm.parsing.ClassInfo
import com.language.lookup.jvm.parsing.FunctionInfo
import com.language.lookup.jvm.rep.asLazyTypeMap
import com.language.lookup.oxide.lazyTransform
import org.jetbrains.annotations.Contract
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

fun Executable.fitsArgTypes(argTypes: List<Type>): Boolean {
    if (parameterCount != argTypes.size) return false

    return parameterTypes
        .zip(parameterAnnotations)
        .zip(argTypes)
        .all { (argument, type) -> argument.fitsType(type) }
}

fun Pair<Class<*>, Array<out Annotation>>.fitsType(type: Type): Boolean {
    val (clazz, annotations) = this
    val isNullable = annotations.isTypeNullable()
    return clazz.canBe(type, strict = true, nullable = isNullable)
}

fun Array<out Annotation>.isTypeNullable() = any { it.javaClass.simpleName == "Nullable" }

suspend fun ReflectType.toType(jvmLookup: JvmLookup, generics: Map<String, Type>): Type {
    return when(val tp = this) {
        is ParameterizedType -> {
            if (tp.typeName in generics) return generics[tp.typeName]!!
            val instanceType = tp.rawType.toType(jvmLookup, generics) as Type.JvmType
            val genericNames = jvmLookup.lookUpGenericsDefinitionOrder(instanceType.signature)
            val genericValues = genericNames.zip(tp.actualTypeArguments).associate { (name, arg) -> name to Type.BroadType.Known(arg.toType(jvmLookup, generics)) }
            Type.BasicJvmType(instanceType.signature, genericValues)
        }
        else -> {
            when (tp.typeName) {
                "void" -> Type.Nothing
                "int" -> Type.IntT
                "double" -> Type.DoubleT
                "boolean" -> Type.BoolUnknown
                in generics -> generics[tp.typeName]!!
                else -> {
                    val signature = SignatureString.fromDotNotation(tp.typeName)
                    Type.BasicJvmType(signature)
                }
            }
        }
    }
}

suspend fun evaluateReturnType(arguments: List<Type>, generics: Map<String, Type.BroadType>, method: Method, jvmLookup: JvmLookup): Type {
    val annotations = method.annotations
    val contract = annotations.firstOrNull { it.javaClass == Contract::class.java } as? Contract
    val annotationPatterns = contract?.value?.let { parseContractString(it) }

    val normalReturnType = method.genericReturnType.toType(jvmLookup, generics.asLazyTypeMap())

    val pattern = annotationPatterns?.firstNotNullOfOrNull { it.matches(arguments) }
    return when(pattern) {
        ContractItem.True -> {
            assert(Type.BoolUnknown.isContainedOrEqualTo(normalReturnType) || Type.Bool.isContainedOrEqualTo(normalReturnType))
            Type.BoolTrue
        }
        ContractItem.False -> {
            assert(Type.BoolUnknown.isContainedOrEqualTo(normalReturnType) || Type.Bool.isContainedOrEqualTo(normalReturnType))
            Type.BoolFalse
        }
        ContractItem.Null -> normalReturnType.join(Type.Null)
        ContractItem.NotNull -> normalReturnType
        ContractItem.Ignore -> normalReturnType
        ContractItem.Fail -> Type.Never
        null -> normalReturnType
    }
}

val ContractAnnotationSignature = SignatureString("org::jetbrains::annotations::Contract")

fun evaluateReturnType(returnType: Type, args: List<Type>, info: FunctionInfo): Type {
    val contract = info.annotations.firstOrNull { it.signatureString == ContractAnnotationSignature } ?: return returnType
    val contractString = contract.values["value"] as String
    val annotationPatterns = parseContractString(contractString)
    val pattern = annotationPatterns.firstNotNullOfOrNull { it.matches(args) }
    return when(pattern) {
        ContractItem.True -> {
            assert(Type.BoolUnknown.isContainedOrEqualTo(returnType) || Type.Bool.isContainedOrEqualTo(returnType))
            Type.BoolTrue
        }
        ContractItem.False -> {
            assert(Type.BoolUnknown.isContainedOrEqualTo(returnType) || Type.Bool.isContainedOrEqualTo(returnType))
            Type.BoolFalse
        }
        ContractItem.Null -> returnType.join(Type.Null)
        ContractItem.NotNull -> returnType
        ContractItem.Ignore -> returnType
        ContractItem.Fail -> Type.Never
        null -> returnType
    }
}