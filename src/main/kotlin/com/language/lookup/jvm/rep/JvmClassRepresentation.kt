package com.language.lookup.jvm.rep

import com.language.TemplatedType
import com.language.codegen.getOrThrow
import com.language.compilation.*
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.modifiers.modifiers
import com.language.compilation.templatedType.matchesImpl
import com.language.compilation.tracking.BroadForge
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.JvmInstanceForge
import com.language.compilation.tracking.join
import com.language.compilation.tracking.toBroadType
import com.language.lookup.IRLookup
import com.language.lookup.jvm.*
import com.language.lookup.jvm.parsing.ClassInfo
import com.language.lookup.jvm.parsing.ExceptionInfo
import com.language.lookup.jvm.parsing.FunctionInfo
import com.language.lookup.oxide.lazyTransform
import org.objectweb.asm.Opcodes
import java.lang.reflect.Field
import java.lang.reflect.Modifier

interface JvmClassRepresentation {
    suspend fun methodHasGenericReturnType(name: String, argTypes: List<Type>, lookup: IRLookup): Boolean

    suspend fun lookupMethod(
        name: String,
        type: InstanceForge,
        generics: Map<String, Type>,
        argTypes: List<InstanceForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): FunctionCandidate?

    suspend fun lookupMethodUnknown(
        name: String,
        type: Type,
        generics: Map<String, Type>,
        argTypes: List<BroadForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): BroadForge?

    suspend fun lookUpAssociatedFunction(
        name: String,
        argTypes: List<InstanceForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup,
        generics: Map<String, Type.Broad>
    ): FunctionCandidate?

    suspend fun lookUpAssociatedFunctionUnknown(
        name: String,
        argTypes: List<BroadForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup,
        generics: Map<String, Type.Broad>
    ): BroadForge?

    suspend fun lookUpField(name: String, generics: Map<String, Type>, lookup: IRLookup): Type?

    suspend fun lookUpStaticField(name: String): Type?

    suspend fun lookupFieldForge(name: String): InstanceForge?

    fun hasInterface(signatureString: SignatureString): Boolean

    suspend fun lookupGenericTypes(name: String, argTypes: List<Type>, lookup: IRLookup): Map<String, Type>?

    suspend fun lookupConstructor(argTypes: List<InstanceForge>, lookup: IRLookup): FunctionCandidate?
    suspend fun lookupConstructorUnknown(argTypes: List<BroadForge>, lookup: IRLookup): BroadForge?

    fun getGenericDefinitionOrder(): List<String>

    suspend fun getErrorTypesConstructor(
        visited: MutableSet<FunctionInfo>,
        argTypes: List<Type>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): Set<SignatureString>

    suspend fun getErrorTypesAssociatedFunction(
        visited: MutableSet<FunctionInfo>,
        name: String,
        argTypes: List<Type>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): Set<SignatureString>

    suspend fun getErrorTypesMethod(
        visited: MutableSet<FunctionInfo>,
        name: String,
        argTypes: List<Type>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): Set<SignatureString>

    val modifiers: Modifiers
}


data class BasicJvmClassRepresentation(
    private val clazz: Class<*>,
    private val methods: Cache<String, JvmMethodRepresentation> = Cache(),
    private val associatedFunctions: Cache<String, JvmStaticMethodRepresentation> = Cache(),
    private val associatedFields: Cache<String, Type> = Cache(),
    private val fields: Cache<String, Field> = Cache()
) : JvmClassRepresentation {

    override val modifiers: Modifiers = parseModifiers()
    private val clazzName= SignatureString.fromDotNotation(this.clazz.name)
    val staticFieldForges: Map<String, InstanceForge> =
        clazz.fields.filter { Modifier.isStatic(it.modifiers) }.associate { it.name to InstanceForge.make(it.type.toForge()) }

    private fun parseModifiers() = modifiers {
        if (hasSuperType(SignatureString("java::lang::Throwable"))) setError()
        if (ReflectModifiers.isPublic(clazz.modifiers)) setPublic()
    }

    override suspend fun methodHasGenericReturnType(name: String, argTypes: List<Type>, lookup: IRLookup): Boolean =
        getMethod(name).hasGenericReturnType(argTypes)

    private fun hasSuperType(signatureString: SignatureString): Boolean {
        var current = clazz.superclass
        while (true) {
            return when (current?.name) {
                signatureString.toDotNotation() -> true
                SignatureString("java::lang::Object").toDotNotation() -> false
                null -> false
                else -> {
                    current = current.superclass
                    continue
                }
            }
        }
    }

    private suspend fun getMethod(name: String): JvmMethodRepresentation {
        methods.get(name)?.let { return it }

        val collectedMethods = clazz.methods.filter { it.name == name && !ReflectModifiers.isStatic(it.modifiers) }
        val rep = JvmMethodRepresentation(
            SignatureString.fromDotNotation(clazz.name),
            clazz.isInterface,
            name,
            collectedMethods.toSet(),
            Cache(),
            clazz.isInterface
        )
        methods.set(name, rep)
        return rep
    }

    private suspend fun getAssociatedFunction(name: String): JvmStaticMethodRepresentation {
        associatedFunctions.get(name)?.let { return it }

        val collectedMethods = clazz.methods.filter { it.name == name && ReflectModifiers.isStatic(it.modifiers) }
        val rep = JvmStaticMethodRepresentation(
            SignatureString.fromDotNotation(clazz.name),
            clazz.isInterface,
            name,
            collectedMethods.toSet(),
            Cache()
        )
        associatedFunctions.set(name, rep)
        return rep
    }

    override suspend fun lookupMethod(
        name: String, type: InstanceForge,
        generics: Map<String, Type>,
        argTypes: List<InstanceForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): FunctionCandidate? =
        getMethod(name).lookupVariant(type, generics, argTypes, jvmLookup, lookup)

    override suspend fun lookupMethodUnknown(
        name: String,
        type: Type,
        generics: Map<String, Type>,
        argTypes: List<BroadForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): BroadForge? {
        return getMethod(name).lookupVariantUnknown(type, generics, argTypes, jvmLookup, lookup)
    }

    override suspend fun lookUpAssociatedFunction(
        name: String, argTypes: List<InstanceForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup,
        generics: Map<String, Type.Broad>
    ): FunctionCandidate? =
        getAssociatedFunction(name).lookupVariant(argTypes, jvmLookup, generics, lookup)

    override suspend fun lookUpAssociatedFunctionUnknown(
        name: String,
        argTypes: List<BroadForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup,
        generics: Map<String, Type.Broad>
    ): BroadForge? {
        TODO("Not yet implemented")
    }

    override suspend fun lookUpField(name: String, generics: Map<String, Type>, lookup: IRLookup): Type? {
        if (fields.contains(name)) {
            return fields.get(name)!!.toForge(generics)
        }
        val field =
            clazz.fields.firstOrNull { it.name == name && !ReflectModifiers.isStatic(it.modifiers) } ?: return null
        fields.set(name, field)
        return field.toForge(generics)
    }

    override suspend fun lookUpStaticField(name: String): Type? {
        if (associatedFields.contains(name)) return associatedFields.get(name)
        val field =

            clazz.fields.firstOrNull { ReflectModifiers.isStatic(it.modifiers) && it.name == name } ?: return null
        val fieldType = field.type.toForge()
        associatedFields.set(name, fieldType)
        return fieldType
    }


    override suspend fun lookupFieldForge(name: String): InstanceForge? {
        return staticFieldForges[name]
    }

    override fun hasInterface(signatureString: SignatureString): Boolean {
        return hasInterface(clazz, signatureString, mutableSetOf())
    }

    override suspend fun lookupGenericTypes(name: String, argTypes: List<Type>, lookup: IRLookup): Map<String, Type>? {
        return getMethod(name).lookupGenericTypes(argTypes, lookup)
    }

    private fun hasInterface(
        clazz: Class<*>,
        signatureString: SignatureString,
        previous: MutableSet<SignatureString>
    ): Boolean {
        return clazz.interfaces.any {
            it.name == signatureString.toDotNotation() || it.interfaces.any { i ->
                val signature = SignatureString.fromDotNotation(i.name)
                if (signature !in previous) {
                    previous.add(signature)
                    hasInterface(i, signatureString, previous)
                } else false
            }
        }
    }


    override suspend fun lookupConstructor(argTypes: List<InstanceForge>, lookup: IRLookup): FunctionCandidate? {
        val constructor = clazz.constructors.firstOrNull { it.fitsArgTypes(argTypes.map { it.type }).second } ?: return null
        val jvmArgs = constructor.parameterTypes.map { it.toForge() }

        val clazzGenerics = clazz.typeParameters.map { it.name }

        val forge = JvmInstanceForge(clazzGenerics.associateWith { InstanceForge.Uninit }.toMutableMap(), clazzName)
        //TODO note that this is complete bs and based on the constructor call we might be able to already infer some generics but its not there yet

        val candidate = SimpleFunctionCandidate(
            oxideArgs = argTypes.map { it.type },
            jvmArgs = jvmArgs,
            jvmReturnType = Type.Nothing,
            oxideReturnType = forge.type,
            invocationType = Opcodes.INVOKESPECIAL,
            jvmOwner = SignatureString.fromDotNotation(clazz.name),
            name = "<init>",
            jvmName = "<init>",
            returnForge = forge,
            obfuscateName = false,
            requireDispatch = false
        )

        return candidate
    }

    override suspend fun lookupConstructorUnknown(argTypes: List<BroadForge>, lookup: IRLookup): BroadForge? {
        clazz.constructors.firstOrNull { it.fitsArgTypes(argTypes.map { it.toBroadType()}).second } ?: return null

        val clazzGenerics = clazz.typeParameters.map { it.name }

        val forge = JvmInstanceForge(clazzGenerics.associateWith {InstanceForge.Uninit  }.toMutableMap(), clazzName)

        return forge
    }

    override fun getGenericDefinitionOrder(): List<String> {
        return clazz.typeParameters.map { it.name }
    }

    override suspend fun getErrorTypesAssociatedFunction(
        visited: MutableSet<FunctionInfo>,
        name: String,
        argTypes: List<Type>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): Set<SignatureString> {

        val info = FunctionInfo(name, clazzName,emptyList(), argTypes.map { it.toTemplatedType() }, TemplatedType.Nothing, emptySet(), ExceptionInfo(emptySet(), emptySet()))

        if (info in visited) {
            return emptySet()
        }

        visited.add(info)
        return getAssociatedFunction(name).getErrorTypes(argTypes)
    }

    override suspend fun getErrorTypesMethod(
        visited: MutableSet<FunctionInfo>,
        name: String,
        argTypes: List<Type>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): Set<SignatureString> {
        val info = FunctionInfo(name, clazzName,emptyList(), argTypes.map { it.toTemplatedType() }, TemplatedType.Nothing, emptySet(), ExceptionInfo(emptySet(), emptySet()))

        if (info in visited) {
            return emptySet()
        }

        visited.add(info)
        return getMethod(name).getErrorTypes(argTypes)
    }

    override suspend fun getErrorTypesConstructor(
        visited: MutableSet<FunctionInfo>,
        argTypes: List<Type>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): Set<SignatureString> {
        val info = FunctionInfo("<init>", clazzName, emptyList(), argTypes.map { it.toTemplatedType() }, TemplatedType.Nothing, emptySet(), ExceptionInfo(emptySet(), emptySet()))

        if (info in visited) {
            return emptySet()
        }

        visited.add(info)

        val constructor = clazz.constructors.firstOrNull { it.fitsArgTypes(argTypes).second } ?: error("No consturctor found")
        return constructor.exceptionTypes.map { SignatureString.fromDotNotation(it.name) }.toSet()
    }
}

fun Type.toTemplatedType(): TemplatedType {
    return when(this) {
        is Type.BoolT -> TemplatedType.BoolT
        Type.DoubleT -> TemplatedType.DoubleT
        Type.IntT -> TemplatedType.IntT
        is Type.Array -> TemplatedType.Array(itemType.getOrThrow("").toTemplatedType())
        Type.BoolArray -> TemplatedType.Array(TemplatedType.BoolT)
        Type.DoubleArray ->  TemplatedType.Array(TemplatedType.DoubleT)
        Type.IntArray ->  TemplatedType.Array(TemplatedType.IntT)
        is Type.JvmType -> TemplatedType.Complex(signature, emptyList())
        is Type.Lambda -> TODO()
        Type.Never ->  TemplatedType.Never
        Type.Nothing ->  TemplatedType.Nothing
        Type.Null -> TemplatedType.Null
        is Type.Union -> TODO()
        Type.UninitializedGeneric -> TODO()
    }
}

data class JvmClassInfoRepresentation(
    val info: ClassInfo
) : JvmClassRepresentation {
    override suspend fun methodHasGenericReturnType(name: String, argTypes: List<Type>, lookup: IRLookup): Boolean {
        return getMethod(name, argTypes, lookup)!!.returnType.isGeneric()
    }

    private suspend fun getMethod(name: String, argTypes: List<Type>, lookup: IRLookup): FunctionInfo? =
        info.methods[name]?.find { it.args.matchesImpl(argTypes, mutableMapOf(), emptyMap(), lookup) }


    private suspend fun getAssociatedFunction(name: String, argTypes: List<Type>, lookup: IRLookup): FunctionInfo? {
        return info.associatedFunctions[name]?.find { it.args.matchesImpl(argTypes, mutableMapOf(), emptyMap(), lookup) }
    }


    override suspend fun lookupMethod(
        name: String,
        type: InstanceForge,
        generics: Map<String, Type>,
        argTypes: List<InstanceForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): FunctionCandidate? {
        val tps = argTypes.map { it.type  }
        val method = getMethod(name, tps, lookup) ?: return null
        val oxideReturnType = with(lookup) {
            val tp = method.returnType.populate(generics)
            evaluateReturnType(tp, tps, method)
        }
        val errorType = getErrorTypesMethod(mutableSetOf(), name, tps, lookup, jvmLookup)

        val actualReturnType = errorType.fold(oxideReturnType) { acc, it -> acc.join(Type.BasicJvmType(it)) }

        val forge = InstanceForge.fromTypeAsJvm(actualReturnType)

        val candidate = SimpleFunctionCandidate(
            listOf(instanceType) + tps,
            method.args.map { it.defaultVariant() },
            method.returnType.defaultVariant(),
            actualReturnType,
            Opcodes.INVOKEVIRTUAL,
            info.signature,
            name,
            name,
            returnForge = forge,
            obfuscateName = false,
            requireDispatch = false,
            requiresCatch = errorType
        )

        return candidate
    }

    override suspend fun lookupMethodUnknown(
        name: String,
        type: Type,
        generics: Map<String, Type>,
        argTypes: List<BroadForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): BroadForge? {
        TODO("Not yet implemented")
    }

    override suspend fun lookUpAssociatedFunction(
        name: String,
        argTypes: List<InstanceForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup,
        generics: Map<String, Type.Broad>
    ): FunctionCandidate? {
        val tps = argTypes.map { it.type  }
        val function = getAssociatedFunction(name,tps, lookup) ?: return null

        val oxideReturnType = evaluateReturnType(function.returnType.defaultVariant(), tps, function)
        val errorType = getErrorTypesAssociatedFunction(mutableSetOf(), name, tps, lookup, jvmLookup)

        val actualReturnType = errorType.fold(oxideReturnType) { acc, it -> acc.join(Type.BasicJvmType(it)) }
        val forge = InstanceForge.fromTypeAsJvm(actualReturnType)

        val candidate = SimpleFunctionCandidate(
            tps,
            function.args.map { it.defaultVariant() },
            function.returnType.defaultVariant(),
            actualReturnType,
            Opcodes.INVOKESTATIC,
            info.signature,
            name,
            name,
            returnForge = forge,
            obfuscateName = false,
            requireDispatch = false,
            requiresCatch = errorType
        )

        return candidate
    }

    override suspend fun lookUpAssociatedFunctionUnknown(
        name: String,
        argTypes: List<BroadForge>,
        lookup: IRLookup,
        jvmLookup: JvmLookup,
        generics: Map<String, Type.Broad>
    ): BroadForge? {
        TODO("Not yet implemented")
    }

    override suspend fun lookUpField(name: String, generics: Map<String, Type>, lookup: IRLookup): Type? {
        val field = info.fields[name]
        val type = with(lookup) {
            field?.populate(generics)
        }
        return type
    }

    override suspend fun lookUpStaticField(name: String): Type? {
        return info.staticFields[name]
    }

    override suspend fun lookupFieldForge(name: String): InstanceForge? {
        return info.staticFields[name]?.let { InstanceForge.make(it) }
    }

    override fun hasInterface(signatureString: SignatureString): Boolean {
        return signatureString in info.interfaces
    }

    override suspend fun lookupGenericTypes(name: String, argTypes: List<Type>, lookup: IRLookup): Map<String, Type>? {
        return getMethod(name, argTypes, lookup)
            ?.args
            ?.zip(argTypes)
            ?.map { (arg, tp) ->
                arg.extractGenerics(tp, lookup)
            }
            ?.reduce { acc, map -> acc + map }
    }

    private val instanceType =
        Type.BasicJvmType(info.signature, info.generics.associate { it.name to Type.UninitializedGeneric })

    override suspend fun lookupConstructor(argTypes: List<InstanceForge>, lookup: IRLookup): FunctionCandidate? {
        val tps = argTypes.map { it.type  }
        val constructor =
            info.constructors.find { it.args.matchesImpl(tps, mutableMapOf(), emptyMap(), lookup) } ?: return null


        val candidate = SimpleFunctionCandidate(
            tps,
            constructor.args.map { it.defaultVariant() },
            jvmReturnType = Type.Nothing,
            oxideReturnType = instanceType,
            Opcodes.INVOKESPECIAL,
            info.signature,
            "<init>",
            "<init>",
            returnForge = JvmInstanceForge(info.generics.associate { it.name to InstanceForge.Uninit }.toMutableMap(), info.signature),
            obfuscateName = false,
            requireDispatch = false
        )

        return candidate
    }

    override suspend fun lookupConstructorUnknown(argTypes: List<BroadForge>, lookup: IRLookup): BroadForge? {
        info.constructors.find { it.args.matchesImpl(argTypes.map { it.toBroadType() }, mutableMapOf(), emptyMap(), lookup) } ?: return null
        return InstanceForge.fromTypeAsJvm(instanceType)
    }

    override fun getGenericDefinitionOrder(): List<String> {
        return info.generics.map { it.name }
    }

    override suspend fun getErrorTypesConstructor(
        visited: MutableSet<FunctionInfo>,
        argTypes: List<Type>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): Set<SignatureString> {
        val constructor =
            info.constructors.find { it.args.matchesImpl(argTypes, modifiers = emptyMap(), lookup = lookup) } ?: error("No constructor found")

        return traceInfo(constructor.exceptionInfo, jvmLookup, visited, lookup)
    }

    private suspend fun traceInfo(exceptionInfo: ExceptionInfo, jvmLookup: JvmLookup, visited: MutableSet<FunctionInfo>, lookup: IRLookup): Set<SignatureString> {
        val exceptions = exceptionInfo.others.flatMap {
            jvmLookup.lookupErrorTypes(
                visited,
                it.owner,
                it.name,
                it.argTypes.map { a -> a.defaultVariant() },
                lookup
            )
        }

        return exceptionInfo.exceptions + exceptions.toSet()
    }

    override suspend fun getErrorTypesAssociatedFunction(
        visited: MutableSet<FunctionInfo>,
        name: String,
        argTypes: List<Type>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): Set<SignatureString> {
        val function = getAssociatedFunction(name, argTypes, lookup) ?: error("No matching function found")

        if (function in visited) {
            return emptySet() //if already in checked chain do not check twice
        }

        visited.add(function)
        return traceInfo(function.exceptionInfo, jvmLookup, visited, lookup)
    }

    override suspend fun getErrorTypesMethod(
        visited: MutableSet<FunctionInfo>,
        name: String,
        argTypes: List<Type>,
        lookup: IRLookup,
        jvmLookup: JvmLookup
    ): Set<SignatureString> {
        val function = getMethod(name, argTypes, lookup) ?: getAssociatedFunction(name, argTypes, lookup)
            ?: error("No matching function found $name:$argTypes")

        if (function in visited) {
            return emptySet() //if already in checked chain do not check twice
        }

        visited.add(function)
        return traceInfo(function.exceptionInfo, jvmLookup, visited, lookup)
    }

    override val modifiers: Modifiers
        get() = info.modifiers

}

fun TemplatedType.defaultVariant(): Type = when (this) {
    is TemplatedType.Array -> Type.Array(Type.Broad.Known(itemType.defaultVariant()))
    TemplatedType.BoolT -> Type.BoolUnknown
    is TemplatedType.Complex -> Type.BasicJvmType(signatureString, emptyMap())
    TemplatedType.DoubleT -> Type.DoubleT
    is TemplatedType.Generic -> Type.Object
    TemplatedType.IntT -> Type.IntT
    TemplatedType.Nothing -> Type.Nothing
    TemplatedType.Null -> Type.Null
    is TemplatedType.Union -> Type.Union(entries = types.map { it.defaultVariant() }.toSet())
    TemplatedType.Never -> Type.Null
}

fun TemplatedType.isGeneric(): Boolean = when (this) {
    is TemplatedType.Array -> itemType.isGeneric()
    is TemplatedType.Complex -> generics.any { it.isGeneric() }
    is TemplatedType.Generic -> true
    is TemplatedType.Union -> types.any { it.isGeneric() }
    else -> false
}

fun TemplatedType.getGenerics(): Set<String> = when (this) {
    is TemplatedType.Array -> itemType.getGenerics()
    is TemplatedType.Complex -> generics.fold(emptySet()) { acc, it -> acc + it.getGenerics() }
    is TemplatedType.Generic -> setOf(name)
    is TemplatedType.Union -> types.fold(emptySet()) { acc, it -> acc + it.getGenerics() }
    else -> emptySet()
}


suspend fun TemplatedType.extractGenerics(type: Type, lookup: IRLookup): Map<String, Type> = when (this) {
    is TemplatedType.Array -> itemType.extractGenerics(
        ((type as Type.Array).itemType as Type.Broad.Known).type,
        lookup
    )

    is TemplatedType.Complex -> (type as Type.JvmType)
        .orderedGenerics(lookup)
        .zip(generics)
        .mapNotNull { (tp, template) ->
            (tp.second as? Type.Broad.Known)?.type?.let {
                template.extractGenerics(
                    it,
                    lookup
                )
            }
        }
        .fold(emptyMap()) { acc, map -> acc + map }

    is TemplatedType.Generic -> mapOf(name to type)
    is TemplatedType.Union -> TODO()
    else -> emptyMap()
}

suspend fun Type.JvmType.orderedGenerics(lookup: IRLookup) =
    genericTypes.orderByKeys(lookup.lookupOrderGenerics(signature))

fun <K, V> Map<K, V>.orderByKeys(keys: List<K>) = keys.map { it to this[it]!! }

fun Map<String, Type.Broad>.asLazyTypeMap() =
    lazyTransform { _, it -> (it as? Type.Broad.Known)?.type ?: Type.Object }

fun InstanceForge.Companion.fromTypeAsJvm(type: Type): InstanceForge {
    if (type.isUnboxedPrimitive() || type.isBoxedPrimitive()) {
        return make(type)
    }

    if (type is Type.BasicJvmType) {
        return JvmInstanceForge(TODO(), type.signature)
    }

    if (type is Type.Union) {
        return type.entries.map { InstanceForge.fromTypeAsJvm(it) }.reduce { acc, forge -> acc.join(forge)  }
    }

    if (type is Type.Nothing) {
        return ConstNothing
    }

    if (type is Type.Never) {
        return ConstNever
    }

    if (type is Type.Null) {
        return ConstNull
    }
    error("Unreachable")
}