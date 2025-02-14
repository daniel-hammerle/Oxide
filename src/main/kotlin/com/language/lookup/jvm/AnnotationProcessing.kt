package com.language.lookup.jvm

import com.language.codegen.getOrNull
import com.language.codegen.toJVMDescriptor
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.isContainedOrEqualTo
import com.language.compilation.join
import com.language.compilation.tracking.*
import com.language.lookup.IRLookup
import com.language.lookup.jvm.contract.ContractItem
import com.language.lookup.jvm.contract.matches
import com.language.lookup.jvm.contract.parseContractString
import com.language.lookup.jvm.parsing.FunctionInfo
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
        return (parameterCount - 1 to parameterTypes.last().componentType.toForge()) to true
    }

    val result2 =  parameterTypes.slice(0..parameterCount-2)
        .zip(parameterAnnotations)
        .zip(argTypes)
        .all { (argument, type) -> type.getOrNull()?.let { argument.fitsType(it) } ?: true  }

    if (result2) {
        return (parameterCount - 1 to parameterTypes.last().componentType.toForge()) to true
    }
    return null to false
}

fun Pair<Class<*>, Array<out Annotation>>.fitsType(type: Type): Boolean {
    val (clazz, annotations) = this
    val isNullable = annotations.isTypeNullable()
    return clazz.canBe(type, strict = true, nullable = isNullable)
}



fun Class<*>.toForge(): Type {
    return when(name) {
        "void", "V" -> Type.Nothing
        "int", "I" -> Type.IntT
        "double", "D" -> Type.DoubleT
        "boolean", "Z" -> Type.BoolUnknown
        else -> {
            if (name.startsWith("[")) {
                return Type.Array(Type.Broad.Known(Class.forName(name.removePrefix("[L").removeSuffix(";")).toForge()))
            }
            val generics = linkedMapOf(*typeParameters.map { it.typeName to Type.UninitializedGeneric }.toTypedArray())
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
        Type.UninitializedGeneric -> true
    }
}

fun Array<out Annotation>.isTypeNullable() = any { it.javaClass.simpleName == "Nullable" }

suspend fun ReflectType.toForge(jvmLookup: JvmLookup, generics: Map<String, InstanceForge>): InstanceForge {
    return when(val tp = this) {
        is ParameterizedType -> {
            if (tp.typeName in generics) return generics[tp.typeName]!! as InstanceForge

            if (tp.typeName.endsWith("[]")) {
                val signature = SignatureString.fromDotNotation(tp.typeName.removeSuffix("[]"))
                val genericNames = jvmLookup.lookUpGenericsDefinitionOrder(signature)
                val genericValues = genericNames.zip(tp.actualTypeArguments).associate { (name, arg) -> name to arg.toForge(jvmLookup, generics) }
                return ArrayInstanceForge(JvmInstanceForge(genericValues.toMutableMap(), signature))
            }

            val instanceType = tp.rawType.toForge(jvmLookup, generics) as Type.JvmType
            val genericNames = jvmLookup.lookUpGenericsDefinitionOrder(instanceType.signature)
            val genericValues = genericNames.zip(tp.actualTypeArguments).associate { (name, arg) -> name to arg.toForge(jvmLookup, generics) }
            JvmInstanceForge(genericValues.toMutableMap(), instanceType.signature)
        }
        is TypeVariable<*> -> {
            generics[tp.name] ?: InstanceForge.Uninit
        }
        else -> {
            when (tp.typeName) {
                "void" -> InstanceForge.ConstNothing
                "int" -> InstanceForge.ConstInt
                "double" -> InstanceForge.ConstDouble
                "boolean" -> InstanceForge.ConstBool
                in generics -> generics[tp.typeName] ?: InstanceForge.Uninit
                "?" -> JvmInstanceForge(mutableMapOf(), SignatureString("java::lang::Object"))
                else -> {
                    if (tp.typeName.endsWith("[]")) {
                        val signature = SignatureString.fromDotNotation(tp.typeName.removeSuffix("[]"))
                        return ArrayInstanceForge(JvmInstanceForge(mutableMapOf(), signature))
                    }
                    val signature = SignatureString.fromDotNotation(tp.typeName)
                    JvmInstanceForge(mutableMapOf(), signature)
                }
            }
        }
    }
}

fun List<Type.Broad>.populate(executable: Executable): List<Type> {
    return executable.parameterTypes.zip(this).map { (reflectType, type) -> type.getOrNull() ?: reflectType.toForge()}
}

suspend fun evaluateReturnType(arguments: List<BroadForge>, generics: Map<String, InstanceForge>, method: Method, jvmLookup: JvmLookup, lookup: IRLookup): BroadForge {
    val annotations = method.annotations
    val contract = annotations.firstOrNull { it.javaClass == Contract::class.java } as? Contract
    val annotationPatterns = contract?.value?.let { parseContractString(it) }

    val normalReturnType = method.genericReturnType.toForge(jvmLookup, generics).let { it as? InstanceForge ?: return it }

    val pattern = annotationPatterns?.firstNotNullOfOrNull { it.matches(arguments.map { it.toBroadType().let { it as? Type.Broad.Known }?.type ?: return@firstNotNullOfOrNull null }) }
    return when(pattern) {
        ContractItem.True -> {
            assert(Type.BoolUnknown.isContainedOrEqualTo(normalReturnType.type) || Type.Bool.isContainedOrEqualTo(normalReturnType.type))
            InstanceForge.ConstBoolFalse
        }
        ContractItem.False -> {
            assert(Type.BoolUnknown.isContainedOrEqualTo(normalReturnType.type) || Type.Bool.isContainedOrEqualTo(normalReturnType.type))
            InstanceForge.ConstBoolFalse
        }
        ContractItem.Null -> InstanceForge.ConstNull
        ContractItem.NotNull -> normalReturnType
        ContractItem.Ignore -> normalReturnType
        ContractItem.Fail -> InstanceForge.ConstNever
        null -> normalReturnType
    }
}

suspend fun genericAppends(arguments: Iterable<ReflectType>, forges: Iterable<BroadForge>, lookup: IRLookup): Map<String, InstanceForge> {
    val changes = mutableMapOf<String, InstanceForge>()

    fun appendChange(name: String, change: InstanceForge) {
        changes[name] = changes[name]?.join(change) ?: change
    }

    for ((arg, forge) in arguments.zip(forges)) {
        if (forge !is InstanceForge) continue
        inspectType(arg, forge, ::appendChange, lookup)
    }

    return changes
}

private suspend fun inspectType(type: ReflectType, forge: InstanceForge, change: (String, InstanceForge) -> Unit, lookup: IRLookup) {
    when {
        type is TypeVariable<*> -> change(type.name, forge)
        type is ParameterizedType && forge is GenericDestructableForge -> {
            val order = lookup.lookupOrderGenerics(SignatureString.fromDotNotation(type.typeName))

            order.zip(type.actualTypeArguments).forEach { (genericName, arg) ->
                inspectType(arg, forge.destructGeneric(genericName), change, lookup)
            }
        }
        type is ParameterizedType && forge is JoinedInstanceForge -> {
            forge.forges.forEach {  childForge ->
                inspectType(type, childForge, change, lookup)
            }
        }
        else -> {}
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