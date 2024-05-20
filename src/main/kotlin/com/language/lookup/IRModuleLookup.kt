package com.language.lookup

import com.language.TemplatedType
import com.language.compilation.*
import com.language.compilation.modifiers.Modifier
import com.language.compilation.modifiers.Modifiers
import com.language.lookup.jvm.JvmLookup
import com.language.lookup.oxide.OxideLookup

class IRModuleLookup(
    private val jvmLookup: JvmLookup,
    private val oxideLookup: OxideLookup
) : IRLookup {

    override suspend fun lookUpGenericTypes(instance: Type, funcName: String, argTypes: List<Type>): Map<String, Int> {
        return oxideLookup.lookUpGenericTypes(instance, funcName, argTypes)
            ?: jvmLookup.lookUpGenericTypes(instance, funcName, argTypes)
            ?: error("No function: $instance.$funcName($argTypes)")
    }

    override suspend fun hasGenericReturnType(instance: Type, funcName: String, argTypes: List<Type>): Boolean {
        //NOTE since oxide generics are implemented differently, this can only be true for jvm functions
        return when(instance) {
            is Type.JvmType -> jvmLookup.hasGenericReturnType(instance, funcName, argTypes)
            else -> false
        }
    }

    override suspend fun lookUpCandidate(
        modName: SignatureString,
        funcName: String,
        argTypes: List<Type>
    ): FunctionCandidate {
        runCatching {
            oxideLookup.lookupFunction(modName, funcName, argTypes, this)
        }.map { return it }
        runCatching {
            oxideLookup.lookupAssociatedExtensionFunction(modName, funcName, argTypes, this)
        }.map { return it }

        return jvmLookup.lookUpAssociatedFunction(modName, funcName, argTypes) ?: error("NO method found $modName.$funcName($argTypes)")
    }

    override suspend fun lookUpCandidate(instance: Type, funcName: String, argTypes: List<Type>): FunctionCandidate {
        val candidate = runCatching { oxideLookup.lookupExtensionMethod(instance, funcName, argTypes, this) }.getOrNull()
        if (candidate != null) return candidate
        return when(instance) {
            is Type.JvmType -> jvmLookup.lookUpMethod(instance, funcName, argTypes) ?: error("No Method found on $instance.$funcName($argTypes)")
            else -> error("No Method found on $instance.$funcName($argTypes)")
        }
    }

    override suspend fun typeHasInterface(type: Type, interfaceType: SignatureString): Boolean {
        //only jvm types can hold concrete interfaces
        return when(type) {
            is Type.JvmType -> jvmLookup.typeHasInterface(type, interfaceType)
            else -> false
        }
    }

    override fun newModFrame(modNames: Set<SignatureString>): IRLookup {
        return IRModuleLookup(jvmLookup, oxideLookup.newModFrame(modNames))
    }

    override suspend fun lookUpConstructor(className: SignatureString, argTypes: List<Type>): FunctionCandidate {
        return runCatching { oxideLookup.lookupConstructor(className, argTypes, this) }.getOrNull()
            ?: jvmLookup.lookupConstructor(className, argTypes)
            ?: error("NO constructor found for $className($argTypes)")
    }

    override suspend fun lookUpFieldType(instance: Type, fieldName: String): Type {
        runCatching { oxideLookup.lookupMemberField(instance, fieldName, this) }.map { return it }
        return when(instance) {
            is Type.JvmType -> jvmLookup.lookUpField(instance, fieldName)
            else -> null
        } ?: error("No field $instance.$fieldName")
    }

    override suspend fun lookUpFieldType(modName: SignatureString, fieldName: String): Type {
        //only jvm classes can have static fields:
        return jvmLookup.lookUpAssociatedField(modName, fieldName) ?: error("No static field $modName.$fieldName")
    }

    override fun lookUpOrderedFields(className: SignatureString): List<Pair<String, TemplatedType>> {
        return oxideLookup.lookupOrderedFields(structName = className)
    }

    override suspend fun hasModifier(instance: Type, modifier: Modifier): Boolean {
        runCatching { oxideLookup.lookupModifiers(instance) }.map { return it.isModifier(modifier) }
        return when(instance) {
            is Type.JvmType -> jvmLookup.getModifiers(instance.signature).isModifier(modifier)
            else -> false
        }
    }

    override suspend fun satisfiesModifiers(instance: Type, modifiers: Modifiers): Boolean {
        runCatching { oxideLookup.lookupModifiers(instance) }.map { return it.hasAllModifiersOf(modifiers) }
        return when(instance) {
            is Type.JvmType -> jvmLookup.getModifiers(instance.signature).hasAllModifiersOf(modifiers)
            else -> false
        }
    }


    private suspend fun getStructGenerics(structSig: SignatureString): Map<String, Modifiers> {
        return runCatching { oxideLookup.lookupStructGenericModifiers(structSig) }.getOrNull() ?: emptyMap()
    }

    override suspend fun TemplatedType.populate(generics: Map<String, Type>): Type = when(this) {
        is TemplatedType.Array -> Type.Array(Type.BroadType.Known(itemType.populate(generics)))
        is TemplatedType.Complex -> {
            val availableGenerics = getStructGenerics(signatureString)
            val entries = availableGenerics.toList().mapIndexed { index, s ->
                s.first to Type.BroadType.Known(this.generics[index].populate(generics))
            }.toTypedArray()
            Type.BasicJvmType(signatureString, linkedMapOf(*entries))
        }
        is TemplatedType.Generic -> generics[name]!!
        TemplatedType.IntT -> Type.IntT
        TemplatedType.DoubleT -> Type.DoubleT
        TemplatedType.BoolT -> Type.BoolUnknown
        TemplatedType.Null -> Type.Null
        is TemplatedType.Union -> Type.Union(types.map { it.populate(generics) }.toSet())
    }
}