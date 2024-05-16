package com.language.lookup.jvm

import com.language.compilation.*
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.modifiers.modifiers
import org.objectweb.asm.Opcodes
import java.lang.reflect.Field
import java.lang.reflect.Method

typealias ReflectModifiers = java.lang.reflect.Modifier
typealias ReflectType = java.lang.reflect.Type

data class JvmClassRepresentation(
    private val clazz: Class<*>,
    private val methods: Cache<String, JvmMethodRepresentation> = Cache(),
    private val associatedFunctions: Cache<String, JvmStaticMethodRepresentation> = Cache(),
    private val associatedFields: Cache<String, Type> = Cache(),
    private val fields: Cache<String, Field> = Cache()
) {

    val modifiers: Modifiers = parseModifiers()

    private fun parseModifiers() = modifiers {
        if (hasSuperType(SignatureString("java::lang::Throwable"))) setError()
        if (ReflectModifiers.isPublic(clazz.modifiers)) setPublic()
    }

    suspend fun methodHasGenericReturnType(name: String, argTypes: List<Type>): Boolean
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

    suspend fun lookupMethod(name: String, generics: Map<String, Type.BroadType>, argTypes: List<Type>): FunctionCandidate? =
        getMethod(name).lookupVariant(generics, argTypes)

    suspend fun lookUpAssociatedFunction(name: String, argTypes: List<Type>): FunctionCandidate? =
        getAssociatedFunction(name).lookupVariant(argTypes)

    suspend fun lookUpField(name: String, generics: Map<String, Type.BroadType>): Type? {
        if (fields.contains(name)) {
            return fields.get(name)!!.toType(generics)
        }
        val field = clazz.fields.firstOrNull { it.name == name && !ReflectModifiers.isStatic(it.modifiers) } ?: return null
        fields.set(name, field)
        return field.toType(generics)
    }

    suspend fun lookUpStaticField(name: String): Type? {
        if (associatedFields.contains(name)) return associatedFields.get(name)
        val field = clazz.fields.firstOrNull { ReflectModifiers.isStatic(it.modifiers) && it.name == name } ?: return null
        val fieldType = field.type.toType()
        associatedFields.set(name, fieldType)
        return fieldType
    }

    fun hasInterface(signatureString: SignatureString): Boolean {
        return clazz.interfaces.any {
            it.name == signatureString.toDotNotation() || it.interfaces.any { i -> hasInterface(SignatureString.fromDotNotation(i.name)) }
        }
    }

    private fun toType() = Type.BasicJvmType(
        SignatureString.fromDotNotation(clazz.name),
        clazz.typeParameters.associate { it.name to Type.BroadType.Unset } as LinkedHashMap<String, Type.BroadType>
    )

    fun lookupConstructor(argTypes: List<Type>): FunctionCandidate? {
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
    suspend fun lookupVariant(argTypes: List<Type>): FunctionCandidate? {
        if (variants.contains(argTypes)) return variants.get(argTypes)

        val method = methods.firstOrNull { it.fitsArgTypes(argTypes) } ?: return null

        val jvmArgTypes = method.parameterTypes.map { it.toType() }
        val candidate =  FunctionCandidate(
            argTypes,
            jvmArgTypes,
            method.returnType.toType(),
            method.returnType.toType(),
            Opcodes.INVOKEVIRTUAL,
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
    private val variants: Cache<Pair<Map<String, Type.BroadType>, List<Type>>, FunctionCandidate>
) {

    fun hasGenericReturnType(argTypes: List<Type>): Boolean =
        methods.first { it.fitsArgTypes(argTypes) }.let { it.genericReturnType.typeName != it.returnType.name }


    suspend fun lookupVariant(generics: Map<String, Type.BroadType>, argTypes: List<Type>): FunctionCandidate? {
        if (variants.contains(generics to argTypes)) return variants.get(generics to argTypes)

        val method = methods.firstOrNull { it.fitsArgTypes(argTypes) } ?: return null

        val oxideReturnType = evaluateReturnType(argTypes, emptyMap(), method)

        val jvmArgTypes = method.parameterTypes.map { it.toType() }
        val candidate =  FunctionCandidate(
            argTypes,
            jvmArgTypes,
            method.returnType.toType(),
            oxideReturnType,
            Opcodes.INVOKEVIRTUAL,
            ownerSignature,
            name,
            obfuscateName = false,
            requireDispatch = false
        )

        variants.set(generics to argTypes, candidate)
        return candidate
    }
}


