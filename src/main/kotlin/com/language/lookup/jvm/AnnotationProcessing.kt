package com.language.lookup.jvm

import com.language.codegen.getOrNull
import com.language.codegen.lazyMap
import com.language.codegen.toJVMDescriptor
import com.language.compilation.*
import com.language.lookup.IRLookup
import com.language.lookup.jvm.contract.ContractItem
import com.language.lookup.jvm.contract.matches
import com.language.lookup.jvm.contract.parseContractString
import com.language.lookup.jvm.parsing.FunctionInfo
import com.language.lookup.jvm.rep.asLazyTypeMap
import org.jetbrains.annotations.Contract
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable

fun Executable.fitsArgTypes(argTypes: Iterable<Type>): Pair<Pair<Int, Type>?, Boolean> = fitsArgTypes(argTypes.map { Type.Broad.Known(it) })


fun Executable.fitsArgTypes(argTypes: List<Type.Broad>): Pair<Pair<Int, Type>?, Boolean> {
    if (isVarArgs) {
        val argDiff = parameterCount - argTypes.size
        if (argDiff !in 0..1) {
            return null to false
        }
    } else {
        if (parameterCount != argTypes.size) return null to false
    }

    val result = parameterTypes
        .zip(parameterAnnotations)
        .zip(argTypes)
        .all { (argument, type) -> type.getOrNull()?.let { argument.fitsType(it) } ?: true }
    if (!isVarArgs) return null to result
    if (result && isVarArgs) {
        return (parameterCount - 1 to parameterTypes.last().componentType.toType()) to true
    }

    val result2 =  parameterTypes.slice(0..parameterCount-2)
        .zip(parameterAnnotations)
        .zip(argTypes)
        .all { (argument, type) -> type.getOrNull()?.let { argument.fitsType(it) } ?: true  }

    if (result2) {
        return (parameterCount - 1 to parameterTypes.last().componentType.toType()) to true
    }
    return null to false
}

fun Pair<Class<*>, Array<out Annotation>>.fitsType(type: Type): Boolean {
    val (clazz, annotations) = this
    val isNullable = annotations.isTypeNullable()
    return clazz.canBe(type, strict = true, nullable = isNullable)
}



fun Class<*>.toType(): Type {
    return when(name) {
        "void", "V" -> Type.Nothing
        "int", "I" -> Type.IntT
        "double", "D" -> Type.DoubleT
        "boolean", "Z" -> Type.BoolUnknown
        else -> {
            if (name.startsWith("[")) {
                return Type.Array(Type.Broad.Known(Class.forName(name.removePrefix("[L").removeSuffix(";")).toType()))
            }
            val generics = linkedMapOf(*typeParameters.map { it.typeName to Type.Broad.Unset as Type.Broad }.toTypedArray())
            Type.BasicJvmType(SignatureString.fromDotNotation(name), generics)
        }
    }
}


fun Class<*>.canBe(type: Type, strict: Boolean = false, nullable: Boolean = false): Boolean {
    if (!strict && (name == "double" || name == "java.lang.Double") && (type == Type.IntT || type == Type.Int)) return true
    if (name == "java.lang.Object") return true
    if (name.startsWith("[")) return false
    return when(type) {
        is Type.JvmType -> SignatureString(this.name.replace(".", "::")) == type.signature || name == "java.lang.Object"
        Type.DoubleT -> name == "double"
        Type.IntT -> name == "int"
        is Type.BoolT -> name == "boolean"
        Type.Nothing -> false
        Type.Null -> true
        is Type.Union -> {
            type.entries.all {
                if (nullable && it is Type.Null) {
                    true
                } else {
                    this.canBe(it, strict, nullable)
                }
            }
        }
        is Type.JvmArray -> name == type.toJVMDescriptor().replace("/", ".")
        Type.Never -> false
        is Type.Lambda ->SignatureString(this.name.replace(".", "::")) == type.signature || name == "java.lang.Object"
    }
}

fun Array<out Annotation>.isTypeNullable() = any { it.javaClass.simpleName == "Nullable" }

suspend fun ReflectType.toType(jvmLookup: JvmLookup, generics: Map<String, Type>): Type {
    return when(val tp = this) {
        is ParameterizedType -> {
            if (tp.typeName in generics) return generics[tp.typeName]!!
            if (tp.typeName.endsWith("[]")) {
                return Type.Array(Type.Broad.Known(Type.BasicJvmType(SignatureString.fromDotNotation(tp.typeName.removeSuffix("[]")))))
            }

            val instanceType = tp.rawType.toType(jvmLookup, generics) as Type.JvmType
            val genericNames = jvmLookup.lookUpGenericsDefinitionOrder(instanceType.signature)
            val genericValues = genericNames.zip(tp.actualTypeArguments).associate { (name, arg) -> name to Type.Broad.Known(arg.toType(jvmLookup, generics)) }
            Type.BasicJvmType(instanceType.signature, genericValues)
        }
        is TypeVariable<*> -> {
            generics[tp.name] ?: error("No generic exists with name `${tp.name}` in $generics")
        }
        else -> {
            when (tp.typeName) {
                "void" -> Type.Nothing
                "int" -> Type.IntT
                "double" -> Type.DoubleT
                "boolean" -> Type.BoolUnknown
                in generics -> generics[tp.typeName]!!
                "?" -> Type.Object
                else -> {
                    if (tp.typeName.endsWith("[]")) {
                        return Type.Array(Type.Broad.Known(Type.BasicJvmType(SignatureString.fromDotNotation(tp.typeName.removeSuffix("[]")))))
                    }
                    val signature = SignatureString.fromDotNotation(tp.typeName)
                    Type.BasicJvmType(signature)
                }
            }
        }
    }
}

fun List<Type.Broad>.populate(executable: Executable): List<Type> {
    return executable.parameterTypes.zip(this).map { (reflectType, type) -> type.getOrNull() ?: reflectType.toType()}
}

suspend fun evaluateReturnType(arguments: List<Type>, generics: Map<String, Type.Broad>, method: Method, jvmLookup: JvmLookup, lookup: IRLookup): Type {
    val annotations = method.annotations
    val contract = annotations.firstOrNull { it.javaClass == Contract::class.java } as? Contract
    val annotationPatterns = contract?.value?.let { parseContractString(it) }

    val methodSpecificGenerics = method.typeParameters.map { it.name }

    val methodGenerics = method.genericParameterTypes
        .zip(arguments)
        .map { (reflectTp, tp) -> reflectTp.extract(tp, lookup) }
        .reduceOrNull { acc, map -> acc + map }
        ?.filter { it.key in methodSpecificGenerics }
        ?: emptyMap()

    val normalReturnType = method.genericReturnType.toType(jvmLookup, generics.asLazyTypeMap() + methodGenerics.asLazyTypeMap())

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