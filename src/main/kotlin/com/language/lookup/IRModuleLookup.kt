package com.language.lookup

import com.language.TemplatedType
import com.language.codegen.asUnboxed
import com.language.compilation.*
import com.language.compilation.modifiers.Modifier
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.variables.VariableManager
import com.language.eval.evalFunction
import com.language.lookup.jvm.JvmLookup
import com.language.lookup.oxide.OxideLookup

class IRModuleLookup(
    private val jvmLookup: JvmLookup,
    private val oxideLookup: OxideLookup
) : IRLookup {

    override suspend fun lookUpGenericTypes(instance: Type, funcName: String, argTypes: List<Type>): Map<String, Type> {
        return lookUpGenericTypesInternal(instance, funcName, argTypes)
    }

    private suspend fun lookUpGenericTypesInternal(
        instance: Type,
        funcName: String,
        argTypes: List<Type>,
        checkUnboxed: Boolean = true,
        checkBoxed: Boolean = true
    ): Map<String, Type> {
        oxideLookup.lookUpGenericTypes(instance, funcName, argTypes, this)?.let { return it }
        if (instance is Type.Union) {
            return instance.entries.map { lookUpGenericTypes(it, funcName, argTypes) }.reduce { acc, map -> acc + map }
        }
        if (checkUnboxed && instance.isUnboxedPrimitive()) {
            return lookUpGenericTypesInternal(instance.asBoxed(), funcName, argTypes, checkUnboxed = false, checkBoxed)
        }
        if (instance !is Type.JvmType) {
            error("No function: $instance.$funcName($argTypes)")
        }
        jvmLookup.lookUpGenericTypes(instance, funcName, argTypes, this)?.let { return it }
        if (checkBoxed && instance.isBoxedPrimitive()) {
            return lookUpGenericTypesInternal(
                instance.asUnboxed(),
                funcName,
                argTypes,
                checkUnboxed,
                checkBoxed = false
            )
        }
        error("No function: $instance.$funcName($argTypes)")
    }

    override suspend fun hasGenericReturnType(instance: Type, funcName: String, argTypes: List<Type>): Boolean {
        //NOTE since oxide generics are implemented differently, this can only be true for jvm functions
        return when (instance) {
            is Type.JvmType -> jvmLookup.hasGenericReturnType(instance, funcName, argTypes, this)
            else -> false
        }
    }

    override suspend fun lookUpCandidate(
        modName: SignatureString,
        funcName: String,
        argTypes: List<Type>,
        history: History,
        generics: Map<String, Type.Broad>,
    ): FunctionCandidate {
        println("Static candidate $modName.$funcName($argTypes)")
        try {
            return oxideLookup.lookupFunction(modName, funcName, argTypes, this, history)
        } catch (e: Exception) {e.printStackTrace()}
        try {
            return oxideLookup.lookupAssociatedExtensionFunction(modName, funcName, argTypes, this, history)
        } catch (_: Exception) { }

        return jvmLookup.lookUpAssociatedFunction(modName, funcName, argTypes, this, generics)
            ?: error("NO method found $modName.$funcName($argTypes)")
    }

    override suspend fun lookUpCandidate(
        instance: Type,
        funcName: String,
        argTypes: List<Type>,
        history: History
    ): FunctionCandidate {
        val candidate = oxideLookup.lookupExtensionMethod(instance, funcName, argTypes, this, history)
        if (candidate != null) return candidate
        return when {
            instance is Type.Union -> {
                val candidates = instance.entries.associateWith { lookUpCandidate(it, funcName, argTypes, history) }
                UnionFunctionCandidate(candidates)
            }

            instance.isUnboxedPrimitive() -> lookUpCandidate(instance.asBoxed(), funcName, argTypes, history)
            instance is Type.JvmType -> jvmLookup.lookUpMethod(instance, funcName, argTypes, this)
                ?: error("No Method found on $instance.$funcName($argTypes)")

            else -> error("No Method found on $instance.$funcName($argTypes)")
        }
    }

    override suspend fun lookUpCandidateUnknown(
        instance: Type,
        funcName: String,
        argTypes: List<Type.Broad>,
        history: History
    ): Type.Broad {
        val candidate = runCatching {
            oxideLookup.lookupExtensionMethodUnknown(instance, funcName, argTypes, this, history)
        }.getOrNull()
        if (candidate != null) return candidate //propagate early
        return when {
            instance is Type.Union -> {
                instance.entries.map { lookUpCandidateUnknown(it, funcName, argTypes, history) }
                    .reduce { acc, broad -> acc.join(broad) }
            }
            instance.isUnboxedPrimitive() -> lookUpCandidateUnknown(instance.asBoxed(), funcName, argTypes, history)
            instance is Type.JvmType -> jvmLookup.lookUpMethodUnknown(instance, funcName, argTypes, this)
                ?: error("No Method found on $instance.$funcName($argTypes)")

            else -> error("No Method found on $instance.$funcName($argTypes)")
        }
    }

    override suspend fun lookUpCandidateUnknown(
        modName: SignatureString,
        funcName: String,
        argTypes: List<Type.Broad>,
        history: History,
        generics: Map<String, Type.Broad>,
    ): Type.Broad {
        runCatching {
            oxideLookup.lookupFunctionUnknown(modName, funcName, argTypes, this, history)
        }.map { return it }
        runCatching {
            oxideLookup.lookupAssociatedExtensionFunctionUnknown(modName, funcName, argTypes, this, history)
        }.map { return it }

        return jvmLookup.lookUpAssociatedFunctionUnknown(modName, funcName, argTypes, this, generics)
            ?: error("NO method found $modName.$funcName($argTypes)")
    }

    override suspend fun typeHasInterface(type: Type, interfaceType: SignatureString): Boolean {
        //only jvm types can hold concrete interfaces
        return when (type) {
            is Type.JvmType -> jvmLookup.typeHasInterface(type, interfaceType)
            else -> false
        }
    }

    override suspend fun processInlining(
        variables: VariableManager,
        instance: TypedInstruction,
        funcName: String,
        args: List<TypedInstruction>,
        untypedArgs: List<Instruction>,
        generics: Map<String, Type>,
        history: History
    ): TypedInstruction? {
        val (function, implGenerics) = runCatching {
            oxideLookup.findExtensionFunction(
                instance.type,
                funcName,
                this
            )
        }.getOrNull() ?: return null
        val isConstEvalAble = args.all { it is TypedInstruction.Const } && instance is TypedInstruction.Const
        return when (function.shouldInline) {
            false -> {
                if (!isConstEvalAble) return null
                runCatching {
                    evalFunction(
                        function,
                        (listOf(instance) + args) as List<TypedInstruction.Const>,
                        this,
                        implGenerics + generics,
                        history
                    )
                }.getOrNull()
            }
            true -> function.generateInlining(
                args,
                untypedArgs,
                variables,
                instance,
                this,
                implGenerics + generics,
                history
            )
        }
    }

    override suspend fun processInlining(
        variables: VariableManager,
        modName: SignatureString,
        funcName: String,
        args: List<TypedInstruction>,
        untypedArgs: List<Instruction>,
        generics: Map<String, Type>,
        history: History
    ): TypedInstruction? {
        val function = runCatching { oxideLookup.findFunction(modName, funcName, this) }.getOrNull() ?: return null
        val isConstEvalAble = args.all { it is TypedInstruction.Const }
        return when (function.shouldInline) {
            false -> {
                if (!isConstEvalAble) return null
                runCatching {
                    evalFunction(
                        function,
                        args as List<TypedInstruction.Const>,
                        this,
                        generics,
                        history
                    )
                }.getOrNull()
            }

            true -> function.generateInlining(
                args,
                untypedArgs,
                variables,
                null,
                this,
                generics,
                history
            )
        }
    }

    override fun newModFrame(modNames: Set<SignatureString>): IRLookup {
        //Generates a new ModFrame
        return IRModuleLookup(jvmLookup, oxideLookup.newModFrame(modNames))
    }

    override suspend fun lookUpConstructor(className: SignatureString, argTypes: List<Type>): FunctionCandidate {
        return oxideLookup.lookupConstructor(className, argTypes, this)
            ?: jvmLookup.lookupConstructor(className, argTypes, this)
            ?: error("NO constructor found for $className($argTypes)")
    }

    override suspend fun lookUpConstructorUnknown(
        className: SignatureString,
        argTypes: List<Type.Broad>
    ): Type.Broad {
        return runCatching { oxideLookup.lookupConstructorUnknown(className, argTypes, this) }.getOrNull()
            ?: jvmLookup.lookupConstructorUnknown(className, argTypes, this)
            ?: error("NO constructor found for $className($argTypes)")
    }

    override suspend fun lookUpFieldType(instance: Type, fieldName: String): Type {
        runCatching { oxideLookup.lookupMemberField(instance, fieldName, this) }.map { return it }
        return when (instance) {
            is Type.JvmType -> jvmLookup.lookUpField(instance, fieldName, this)
            else -> null
        } ?: error("No field $instance.$fieldName")
    }

    override suspend fun lookUpPhysicalFieldType(
        instance: Type,
        fieldName: String
    ): Type {
        runCatching { oxideLookup.lookupPhysicalField(instance, fieldName, this) }.map { return it }
        return when (instance) {
            is Type.JvmType -> jvmLookup.lookUpField(instance, fieldName, this)
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

    override suspend fun lookupOrderGenerics(className: SignatureString): List<String> {
        TODO("Not yet implemented")
    }

    override suspend fun hasModifier(instance: Type, modifier: Modifier): Boolean {
        runCatching { oxideLookup.lookupModifiers(instance) }.map { return it.isModifier(modifier) }
        return when (instance) {
            is Type.JvmType -> jvmLookup.getModifiers(instance.signature).isModifier(modifier)
            else -> false
        }
    }

    override suspend fun satisfiesModifiers(instance: Type, modifiers: Modifiers): Boolean {
        runCatching { oxideLookup.lookupModifiers(instance) }.map { return it.hasAllModifiersOf(modifiers) }
        return when (instance) {
            is Type.JvmType -> jvmLookup.getModifiers(instance.signature).hasAllModifiersOf(modifiers)
            else -> false
        }
    }

    override suspend fun lookupLambdaInit(
        signatureString: SignatureString,
    ): FunctionCandidate {
        return oxideLookup.lookupLambdaInit(signatureString)
    }

    override suspend fun lookupLambdaInvoke(
        signatureString: SignatureString,
        argTypes: List<Type>,
        history: History
    ): FunctionCandidate {
        return oxideLookup.lookupLambdaInvoke(signatureString, argTypes, this, history)
    }


    private suspend fun getStructGenericNames(structSig: SignatureString): List<String> {
        return runCatching { oxideLookup.lookupStructGenericModifiers(structSig).keys.toList() }.getOrNull()
            ?: jvmLookup.lookUpGenericsDefinitionOrder(structSig)
    }

    override suspend fun TemplatedType.populate(generics: Map<String, Type>, box: Boolean): Type = when (this) {
        is TemplatedType.Array -> Type.Array(Type.Broad.Known(itemType.populate(generics)))
        is TemplatedType.Complex -> {
            val availableGenerics = getStructGenericNames(signatureString)
            val entries = availableGenerics.toList().mapIndexed { index, s ->
                s to (this.generics.getOrNull(index)?.populate(generics)?.let { Type.Broad.Known(it) } ?: Type.Broad.Unset)
            }.toMap()

            Type.BasicJvmType(signatureString, entries)
        }

        TemplatedType.Nothing -> Type.Nothing
        is TemplatedType.Generic -> generics[name]!!.let { if (box) it.asBoxed() else it }
        TemplatedType.IntT -> Type.IntT
        TemplatedType.DoubleT -> Type.DoubleT
        TemplatedType.BoolT -> Type.BoolUnknown
        TemplatedType.Null -> Type.Null
        is TemplatedType.Union -> Type.Union(types.map { it.populate(generics) }.toSet())
        TemplatedType.Never -> Type.Never
    }
}