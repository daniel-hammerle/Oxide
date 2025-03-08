// Copyright 2025 Daniel Hammerle
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.language.lookup

import com.language.TemplatedType
import com.language.codegen.asUnboxed
import com.language.compilation.*
import com.language.compilation.modifiers.Modifier
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.tracking.BasicInstanceForge
import com.language.compilation.tracking.BroadForge
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.JoinedInstanceForge
import com.language.compilation.tracking.JvmInstanceForge
import com.language.compilation.tracking.StructInstanceForge
import com.language.compilation.tracking.join
import com.language.compilation.variables.VariableManager
import com.language.lookup.jvm.JvmLookup
import com.language.lookup.oxide.OxideLookup
import evalFunction

class IRModuleLookup(
    private val jvmLookup: JvmLookup,
    private val oxideLookup: OxideLookup
) : IRLookup {

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
        argTypes: List<InstanceForge>,
        history: History,
        generics: Map<String, Type.Broad>,
    ): FunctionCandidate {
        println("Static candidate $modName.$funcName($argTypes)")
        oxideLookup.lookupFunction(modName, funcName, argTypes, this, history)?.let { return it }
        try {
            return oxideLookup.lookupAssociatedExtensionFunction(modName, funcName, argTypes, this, history)
        } catch (_: Exception) { }

        return jvmLookup.lookUpAssociatedFunction(modName, funcName, argTypes, this, generics)
            ?: error("NO method found $modName.$funcName($argTypes)")
    }

    override suspend fun lookUpCandidate(
        instance: InstanceForge,
        funcName: String,
        argTypes: List<InstanceForge>,
        history: History
    ): FunctionCandidate {
        val instanceType = instance.type
        val candidate = oxideLookup.lookupExtensionMethod(instance, funcName, argTypes, this, history)
        if (candidate != null) return candidate
        return when {
             instance is JoinedInstanceForge -> {
                val candidates = instance.forges.associate { it.type to lookUpCandidate(it, funcName, argTypes, history) }
                UnionFunctionCandidate(candidates)
            }

            instance.type.isUnboxedPrimitive() -> lookUpCandidate(BasicInstanceForge(instanceType.asBoxed()), funcName, argTypes, history)
            else -> jvmLookup.lookUpMethod(instance, funcName, argTypes, this)
                ?: error("No Method found: ${instance.type}.$funcName(${argTypes.joinToString(", ")})")

        }
    }

    override suspend fun lookUpCandidateUnknown(
        instance: InstanceForge,
        funcName: String,
        argTypes: List<BroadForge>,
        history: History
    ): BroadForge {
        val candidate = runCatching {
            oxideLookup.lookupExtensionMethodUnknown(instance, funcName, argTypes, this, history)
        }.getOrNull()
        if (candidate != null) return candidate //propagate early
        return when {
            instance is JoinedInstanceForge -> {
                instance.forges.map { lookUpCandidateUnknown(it, funcName, argTypes, history) }
                    .reduce { acc, broad -> acc.join(broad) }
            }
            instance.type.isUnboxedPrimitive() -> lookUpCandidateUnknown(InstanceForge.make(instance.type.asBoxed()), funcName, argTypes, history)
            instance is JvmInstanceForge -> jvmLookup.lookUpMethodUnknown(instance, funcName, argTypes, this)
                ?: error("No Method found on $instance.$funcName($argTypes)")

            else -> error("No Method found on $instance.$funcName($argTypes)")
        }
    }

    override suspend fun lookUpCandidateUnknown(
        modName: SignatureString,
        funcName: String,
        argTypes: List<BroadForge>,
        history: History,
        generics: Map<String, Type.Broad>,
    ): BroadForge {
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

    override suspend fun lookUpConstructor(className: SignatureString, argTypes: List<InstanceForge>, history: History): FunctionCandidate {
        return oxideLookup.lookupConstructor(className, argTypes, this, history)
            ?: jvmLookup.lookupConstructor(className, argTypes, this)
            ?: error("NO constructor found for $className($argTypes)")
    }

    override suspend fun lookUpConstructorUnknown(
        className: SignatureString,
        argTypes: List<BroadForge>
    ): BroadForge {
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

    override suspend fun lookUpFieldForge(
        modName: SignatureString,
        fieldName: String
    ): InstanceForge {
        return jvmLookup.lookupFieldForge(modName, fieldName) ?: error("Not found")
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
        argTypes: List<InstanceForge>,
        history: History
    ): FunctionCandidate {
        return oxideLookup.lookupLambdaInvoke(signatureString, argTypes, this, history)
    }


    private suspend fun getStructGenericNames(structSig: SignatureString): List<String> {
        return runCatching { oxideLookup.lookupStructGenericModifiers(structSig).keys.toList() }.getOrNull()
            ?: jvmLookup.lookUpGenericsDefinitionOrder(structSig)
    }

    override suspend fun TemplatedType.populate(generics: Map<String, Type>, box: Boolean): Type = when (this) {
        is TemplatedType.Array -> Type.Array(itemType.populate(generics))
        is TemplatedType.Complex -> {
            val availableGenerics = getStructGenericNames(signatureString)
            val entries = availableGenerics.toList().mapIndexed { index, s ->
                s to (this.generics.getOrNull(index)?.populate(generics) ?: Type.UninitializedGeneric)
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
        TemplatedType.ByteT -> Type.ByteT
        TemplatedType.CharT -> Type.CharT
        TemplatedType.FloatT -> Type.FloatT
        TemplatedType.LongT -> Type.LongT
    }
}