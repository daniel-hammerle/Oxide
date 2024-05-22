package com.language.lookup.jvm.rep

import com.language.TemplatedType
import com.language.compilation.*
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.modifiers.modifiers
import com.language.compilation.templatedType.matches
import com.language.lookup.IRLookup
import com.language.lookup.jvm.*
import com.language.lookup.jvm.parsing.ClassInfo
import com.language.lookup.jvm.parsing.FunctionInfo
import com.language.lookup.oxide.lazyTransform
import org.objectweb.asm.Opcodes
import java.lang.reflect.Field

interface JvmClassRepresentation {
    suspend fun methodHasGenericReturnType(name: String, argTypes: List<Type>, lookup: IRLookup): Boolean

    suspend fun lookupMethod(name: String, generics: Map<String, Type.BroadType>, argTypes: List<Type>, lookup: IRLookup): FunctionCandidate?

    suspend fun lookUpAssociatedFunction(name: String, argTypes: List<Type>, lookup: IRLookup): FunctionCandidate?

    suspend fun lookUpField(name: String, generics: Map<String, Type.BroadType>, lookup: IRLookup): Type?

    suspend fun lookUpStaticField(name: String): Type?

    fun hasInterface(signatureString: SignatureString): Boolean

    suspend fun lookupConstructor(argTypes: List<Type>, lookup: IRLookup): FunctionCandidate?

    val modifiers: Modifiers
}


data class BasicJvmClassRepresentation(
    private val clazz: Class<*>,
    private val methods: Cache<String, JvmMethodRepresentation> = Cache(),
    private val associatedFunctions: Cache<String, JvmStaticMethodRepresentation> = Cache(),
    private val associatedFields: Cache<String, Type> = Cache(),
    private val fields: Cache<String, Field> = Cache()
): JvmClassRepresentation {

    override val modifiers: Modifiers = parseModifiers()

    private fun parseModifiers() = modifiers {
        if (hasSuperType(SignatureString("java::lang::Throwable"))) setError()
        if (ReflectModifiers.isPublic(clazz.modifiers)) setPublic()
    }

    override suspend fun methodHasGenericReturnType(name: String, argTypes: List<Type>, lookup: IRLookup): Boolean
            = getMethod(name).hasGenericReturnType(argTypes)

    private fun hasSuperType(signatureString: SignatureString): Boolean {
        var current = clazz.superclass
        while (true) {
            return when(current.name) {
                signatureString.toDotNotation() -> true
                SignatureString("java::lang::Object").toDotNotation() -> false
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
        val rep = JvmMethodRepresentation(SignatureString.fromDotNotation(clazz.name), name, collectedMethods.toSet(), Cache())
        methods.set(name, rep)
        return rep
    }

    private suspend fun getAssociatedFunction(name: String): JvmStaticMethodRepresentation {
        associatedFunctions.get(name)?.let { return it }

        val collectedMethods = clazz.methods.filter { it.name == name && ReflectModifiers.isStatic(it.modifiers) }
        val rep = JvmStaticMethodRepresentation(SignatureString.fromDotNotation(clazz.name), name, collectedMethods.toSet(), Cache())
        associatedFunctions.set(name, rep)
        return rep
    }

    override suspend fun lookupMethod(name: String, generics: Map<String, Type.BroadType>, argTypes: List<Type>, lookup: IRLookup): FunctionCandidate? =
        getMethod(name).lookupVariant(generics, argTypes)

    override suspend fun lookUpAssociatedFunction(name: String, argTypes: List<Type>, lookup: IRLookup): FunctionCandidate? =
        getAssociatedFunction(name).lookupVariant(argTypes)

    override suspend fun lookUpField(name: String, generics: Map<String, Type.BroadType>, lookup: IRLookup): Type? {
        if (fields.contains(name)) {
            return fields.get(name)!!.toType(generics)
        }
        val field = clazz.fields.firstOrNull { it.name == name && !ReflectModifiers.isStatic(it.modifiers) } ?: return null
        fields.set(name, field)
        return field.toType(generics)
    }

    override suspend fun lookUpStaticField(name: String): Type? {
        if (associatedFields.contains(name)) return associatedFields.get(name)
        val field = clazz.fields.firstOrNull { ReflectModifiers.isStatic(it.modifiers) && it.name == name } ?: return null
        val fieldType = field.type.toType()
        associatedFields.set(name, fieldType)
        return fieldType
    }

    override fun hasInterface(signatureString: SignatureString): Boolean {
        return clazz.interfaces.any {
            it.name == signatureString.toDotNotation() || it.interfaces.any { i -> hasInterface(SignatureString.fromDotNotation(i.name)) }
        }
    }

    private fun toType() = Type.BasicJvmType(
        SignatureString.fromDotNotation(clazz.name),
        clazz.typeParameters.associate { it.name to Type.BroadType.Unset }
    )

    override suspend fun lookupConstructor(argTypes: List<Type>, lookup: IRLookup): FunctionCandidate? {
        val constructor = clazz.constructors.firstOrNull { it.fitsArgTypes(argTypes) } ?: return null
        val jvmArgs = constructor.parameterTypes.map { it.toType() }

        val candidate = FunctionCandidate(
            oxideArgs = argTypes,
            jvmArgs = jvmArgs,
            jvmReturnType = Type.Nothing,
            oxideReturnType = this.toType(),
            invocationType = Opcodes.INVOKESPECIAL,
            jvmOwner = SignatureString.fromDotNotation(clazz.name),
            name ="<init>",
            obfuscateName = false,
            requireDispatch = false
        )

        return candidate
    }
}

data class JvmClassInfoRepresentation(
    val info: ClassInfo
) : JvmClassRepresentation {
    override suspend fun methodHasGenericReturnType(name: String, argTypes: List<Type>, lookup: IRLookup): Boolean {
        return getMethod(name, argTypes, lookup)!!.returnType.isGeneric()
    }

    private suspend fun getMethod(name: String, argTypes: List<Type>, lookup: IRLookup): FunctionInfo? {
        return info.methods[name]?.find { it.args.matches(argTypes, mutableMapOf(), emptyMap(), lookup) }
    }

    private suspend fun getAssociatedFunction(name: String, argTypes: List<Type>, lookup: IRLookup): FunctionInfo? {
        return info.associatedFunctions[name]?.find { it.args.matches(argTypes, mutableMapOf(), emptyMap(), lookup) }
    }


    override suspend fun lookupMethod(
        name: String,
        generics: Map<String, Type.BroadType>,
        argTypes: List<Type>,
        lookup: IRLookup,
    ): FunctionCandidate? {
        val method = getMethod(name, argTypes, lookup) ?: return null
        val oxideReturnType = with(lookup) {
            val tp = method.returnType.populate(generics.asLazyTypeMap())
            evaluateReturnType(tp, argTypes, method)
        }
        val candidate = FunctionCandidate(
            argTypes,
            method.args.map { it.defaultVariant() },
            method.returnType.defaultVariant(),
            oxideReturnType,
            Opcodes.INVOKEVIRTUAL,
            info.signature,
            name,
            obfuscateName = false,
            requireDispatch = false
        )

        return candidate
    }

    override suspend fun lookUpAssociatedFunction(name: String, argTypes: List<Type>, lookup: IRLookup): FunctionCandidate? {
        val function = getAssociatedFunction(name, argTypes, lookup) ?: return null

        val oxideReturnType = evaluateReturnType(function.returnType.defaultVariant(), argTypes, function)

        val candidate = FunctionCandidate(
            argTypes,
            function.args.map { it.defaultVariant() },
            function.returnType.defaultVariant(),
            oxideReturnType,
            Opcodes.INVOKESTATIC,
            info.signature,
            name,
            obfuscateName = false,
            requireDispatch = false
        )

        return candidate
    }

    override suspend fun lookUpField(name: String, generics: Map<String, Type.BroadType>, lookup: IRLookup): Type? {
        val field = info.fields[name]
        val type = with(lookup) {
            field?.populate(generics.asLazyTypeMap())
        }
        return type
    }

    override suspend fun lookUpStaticField(name: String): Type? {
        return info.staticFields[name]
    }

    override fun hasInterface(signatureString: SignatureString): Boolean {
        return signatureString in info.interfaces
    }

    override suspend fun lookupConstructor(argTypes: List<Type>, lookup: IRLookup): FunctionCandidate? {
        val constructor = info.constructors.find { it.args.matches(argTypes, mutableMapOf(), emptyMap(), lookup) } ?: return null
        val instanceType = Type.BasicJvmType(info.signature, info.generics.associate { it.name to Type.BroadType.Unset })

        val candidate = FunctionCandidate(
            argTypes,
            constructor.args.map { it.defaultVariant() },
            jvmReturnType = Type.Nothing,
            oxideReturnType = instanceType,
            Opcodes.INVOKESPECIAL,
            info.signature,
            "<init>",
            obfuscateName = false,
            requireDispatch = false
        )

        return candidate
    }

    override val modifiers: Modifiers
        get() = info.modifiers

}

fun TemplatedType.defaultVariant(): Type = when(this) {
    is TemplatedType.Array -> Type.Array(Type.BroadType.Known(itemType.defaultVariant()))
    TemplatedType.BoolT -> Type.BoolUnknown
    is TemplatedType.Complex -> Type.BasicJvmType(signatureString, emptyMap())
    TemplatedType.DoubleT -> Type.DoubleT
    is TemplatedType.Generic -> Type.Object
    TemplatedType.IntT -> Type.IntT
    TemplatedType.Nothing -> Type.Nothing
    TemplatedType.Null -> Type.Null
    is TemplatedType.Union -> Type.Union(entries = types.map { it.defaultVariant() }.toSet())
}

fun TemplatedType.isGeneric(): Boolean = when(this) {
    is TemplatedType.Array -> itemType.isGeneric()
    is TemplatedType.Complex -> generics.any { it.isGeneric() }
    is TemplatedType.Generic -> true
    is TemplatedType.Union -> types.any { it.isGeneric() }
    else -> false
}

fun Map<String, Type.BroadType>.asLazyTypeMap() = lazyTransform { (it as? Type.BroadType.Known)?.type ?: error("No Type found") }