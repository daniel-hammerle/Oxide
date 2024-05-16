package com.language.lookup.jvm

import com.language.compilation.*
import com.language.lookup.jvm.contract.ContractItem
import com.language.lookup.jvm.contract.matches
import com.language.lookup.jvm.contract.parseContractString
import org.jetbrains.annotations.Contract
import java.lang.reflect.Executable
import java.lang.reflect.Method

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

fun evaluateReturnType(arguments: List<Type>, generics: Map<String, Type.BroadType>, method: Method): Type {
    val contract = method.annotations.firstOrNull { it.javaClass == Contract::class.java } as? Contract
    val annotationPatterns = contract?.value?.let { parseContractString(it) }

    val normalReturnType = when(val tp = generics[method.genericReturnType.typeName]) {
        is Type.BroadType.Known -> tp.type
        Type.BroadType.Unset -> Type.Object
        null -> method.returnType.toType()
    }

    val pattern = annotationPatterns?.firstNotNullOfOrNull { it.matches(arguments) }
    return when(pattern) {
        ContractItem.True -> {
            assert(Type.BoolUnknown.isContainedOrEqualTo(normalReturnType) || Type.Bool.isContainedOrEqualTo(normalReturnType))
            Type.BoolUnknown
        }
        ContractItem.False -> {
            assert(Type.BoolUnknown.isContainedOrEqualTo(normalReturnType) || Type.Bool.isContainedOrEqualTo(normalReturnType))
            Type.BoolUnknown
        }
        ContractItem.Null -> normalReturnType.join(Type.Null)
        ContractItem.NotNull -> normalReturnType
        ContractItem.Ignore -> normalReturnType
        ContractItem.Fail -> Type.Never
        null -> normalReturnType
    }
}

