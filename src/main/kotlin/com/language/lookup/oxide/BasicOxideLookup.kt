package com.language.lookup.oxide

import com.language.TemplatedType
import com.language.compilation.*
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.templatedType.matchesImpl
import com.language.compilation.templatedType.matchesSubset
import com.language.lookup.IRLookup
import com.language.lookup.jvm.rep.asLazyTypeMap
import com.language.lookup.jvm.rep.defaultVariant
import org.objectweb.asm.Opcodes

class BasicOxideLookup(
    private val modules: Map<SignatureString, IRModule>,
    private val allowedImplBlocks: Map<TemplatedType, Set<IRImpl>>
) : OxideLookup {
    override fun newModFrame(modNames: Set<SignatureString>): OxideLookup {
        val newImplBlocks: MutableMap<TemplatedType, MutableSet<IRImpl>> = mutableMapOf()
        modules.filter { it.key in modNames }.forEach { (_, mod) ->
            mod.implBlocks.forEach { (type, block) ->
                newImplBlocks[type]?.addAll(block) ?: newImplBlocks.put(type, block.toMutableSet())
            }
        }

        return BasicOxideLookup(modules, newImplBlocks)
    }

    override suspend fun lookUpGenericTypes(
        instance: Type,
        funcName: String,
        argTypes: List<Type>,
        lookup: IRLookup
    ): Map<String, Type>? {
        //this can never happen in native methods since we don't have static type annotations
        return if (hasExtensionMethod(instance, funcName, lookup)) {
            emptyMap()
        } else null
    }

    private suspend fun hasExtensionMethod(instance: Type, funcName: String, lookup: IRLookup): Boolean {
        for ((template, blocks) in allowedImplBlocks) {
            val generics = mutableMapOf<String, Type>()
            blocks.forEach { impl ->
                if (funcName in impl.methods && template.matchesImpl(instance, generics, impl.genericModifiers, lookup)) {
                    return true
                }
            }
        }
        return false
    }

    override suspend fun lookupFunction(
        module: SignatureString,
        funcName: String,
        args: List<Type>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate {
        val mod = modules[module] ?: error("No Module found with name `$module`")
        val func = mod.functions[funcName] ?: error("No Function `$funcName` on module `$module`")
        val returnType =
            (func as BasicIRFunction).inferTypes(
                args,
                lookup,
                emptyMap(),
                history
            )

        return SimpleFunctionCandidate(
            oxideArgs = args,
            jvmArgs = args.map { it.toActualJvmType() },
            jvmReturnType = returnType.toActualJvmType(),
            oxideReturnType = returnType,
            invocationType = Opcodes.INVOKESTATIC,
            jvmOwner = module,
            name = funcName,
            obfuscateName = true,
            requireDispatch = false
        )
    }

    override suspend fun lookupFunctionUnknown(
        module: SignatureString,
        funcName: String,
        args: List<Type.Broad>,
        lookup: IRLookup,
        history: History
    ): Type.Broad {
        val mod = modules[module] ?: error("No Module found with name `$module`")
        val func = mod.functions[funcName] ?: error("No Function `$funcName` on module `$module`")
        val returnType = runCatching {
            (func as BasicIRFunction).inferUnknown(
                args,
                lookup,
                emptyMap(),
                history
            )
        }.getOrElse { it.printStackTrace(); throw it }

        return returnType
    }


    override suspend fun lookupExtensionMethod(
        instance: Type,
        funcName: String,
        args: List<Type>, lookup: IRLookup,
        history: History
    ): FunctionCandidate? {
        val (func, generics, impl) = findExtensionMethod(instance, funcName, lookup) ?: return null

        val returnType = runCatching {
            (func as BasicIRFunction).inferTypes(
                listOf(instance) + args,
                lookup,
                generics,
                history
            )
        }.getOrElse { it.printStackTrace(); throw it }

        return SimpleFunctionCandidate(
            oxideArgs = listOf(instance) + args,
            jvmArgs = listOf(instance) + args.map { it.toActualJvmType() },
            jvmReturnType = returnType.toActualJvmType(),
            oxideReturnType = returnType,
            Opcodes.INVOKESTATIC,
            impl.fullSignature,
            funcName,
            obfuscateName = true,
            requireDispatch = false
        )

    }

    private suspend inline fun findExtensionMethod(instance: Type, funcName: String, lookup: IRLookup): Triple<IRFunction, Map<String, Type>, IRImpl>? {
        for ((template, blocks) in allowedImplBlocks) {
            val generics = mutableMapOf<String, Type>()
            val impl = blocks.firstOrNull { impl ->
                funcName in impl.methods && template.matchesImpl(instance, generics, impl.genericModifiers, lookup)
            } ?: continue

            val func = impl.methods[funcName]!!
            return Triple(func, generics, impl)
        }
        return null
    }


    override suspend fun lookupExtensionMethodUnknown(
        instance: Type,
        funcName: String,
        args: List<Type.Broad>,
        lookup: IRLookup,
        history: History
    ): Type.Broad {
        val (func, generics) = findExtensionMethod(instance, funcName, lookup)!!

        return (func as BasicIRFunction).inferUnknown(args, lookup, generics, history)
    }

    override suspend fun lookupAssociatedExtensionFunction(
        structName: SignatureString,
        funcName: String,
        args: List<Type>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate {
        val impl = allowedImplBlocks
            .asSequence()
            .filter {(template, _) -> template !is TemplatedType.Complex || template.signatureString == structName }
            .firstNotNullOf { (_, blocks) -> blocks.find { funcName in it.associatedFunctions }  }

        val func = impl.associatedFunctions[funcName]!!
        val returnType = (func as BasicIRFunction).inferTypes(args, lookup, emptyMap(), history)
        return SimpleFunctionCandidate(
            oxideArgs = args,
            jvmArgs = args.map { it.toActualJvmType() },
            jvmReturnType = returnType.toActualJvmType(),
            oxideReturnType = returnType,
            Opcodes.INVOKESTATIC,
            impl.fullSignature,
            name = funcName,
            obfuscateName = true,
            requireDispatch = false
        )
    }

    override suspend fun lookupAssociatedExtensionFunctionUnknown(
        structName: SignatureString,
        funcName: String,
        args: List<Type.Broad>,
        lookup: IRLookup,
        history: History
    ): Type.Broad {
        val impl = allowedImplBlocks
            .asSequence()
            .filter {(template, _) -> template !is TemplatedType.Complex || template.signatureString == structName }
            .firstNotNullOf { (_, blocks) -> blocks.find { funcName in it.associatedFunctions }  }

        val func = impl.associatedFunctions[funcName]!!
        val returnType = (func as BasicIRFunction).inferUnknown(args, lookup, emptyMap(), history)

        return returnType
    }

    override suspend fun lookupConstructor(
        structName: SignatureString,
        args: List<Type>,
        lookup: IRLookup
    ): FunctionCandidate? {
        val struct = getStruct(structName) ?: return null
        val fields = struct.getDefaultVariant(lookup)
        if (fields.size != args.size) error("Invalid arg count")


        val generics = mutableMapOf<String, Type>()

        val result = struct
            .fields
            .toList()
            .zip(args)
            .all { (field, tp) -> field.second.matchesSubset(tp, generics, struct.generics, lookup) }

        if (!result) {
            error("Types did not match")
        }

        return SimpleFunctionCandidate(
            oxideArgs = args,
            jvmArgs = fields.values.toList(),
            jvmReturnType = Type.Nothing,
            oxideReturnType = Type.BasicJvmType(structName, generics.mapValues { Type.Broad.Known(it.value) }),
            invocationType = Opcodes.INVOKESPECIAL,
            structName,
            "<init>",
            obfuscateName = false,
            requireDispatch = false
        )
    }

    override suspend fun lookupConstructorUnknown(
        structName: SignatureString,
        args: List<Type.Broad>,
        lookup: IRLookup
    ): Type.Broad {
        val struct = getStruct(structName) ?: error("Cannot find struct `$structName`")
        val fields = struct.getDefaultVariant(lookup)
        if (fields.size != args.size) error("Invalid arg count")


        val generics = mutableMapOf<String, Type>()

        val result = struct
            .fields
            .toList()
            .zip(args)
            .all { (field, tp) -> field.second.matchesImpl(tp, generics, struct.generics, lookup) }

        if (!result) error("Types did not match")

        return Type.Broad.Known(Type.BasicJvmType(structName, generics.mapValues { Type.Broad.Known(it.value) }))
    }

    override suspend fun lookupMemberField(instance: Type, name: String, lookup: IRLookup): Type {
        return when (instance) {
            is Type.JvmType -> {
                val type = getStruct(instance.signature)?.fields?.get(name)
                val transformedGenerics = instance.genericTypes.asLazyTypeMap()
                with(lookup) { type?.populate(transformedGenerics, true) } ?: error("No field $instance.$name")
            }

            else -> error("$instance does not have the field `$name`")
        }
    }

    override suspend fun lookupPhysicalField(
        instance: Type,
        name: String,
        lookup: IRLookup
    ): Type {
        return when (instance) {
            is Type.JvmType -> {
                val type = getStruct(instance.signature)?.fields?.get(name)
                return type?.defaultVariant()  ?: error("No field $instance.$name")
            }

            else -> error("$instance does not have the field `$name`")
        }
    }

    override suspend fun lookupLambdaInit(signatureString: SignatureString): FunctionCandidate {
        val lambda = modules[signatureString.modName]?.getLambda(signatureString)!!
        val args = lambda.captures.values.toList()
        return SimpleFunctionCandidate(
            args,
            args,
            Type.Nothing,
            Type.Lambda(signatureString),
            Opcodes.INVOKESPECIAL,
            signatureString,
            "<init>",
            obfuscateName = false,
            requireDispatch = false
        )
    }

    override suspend fun lookupLambdaInvoke(
        signatureString: SignatureString,
        argTypes: List<Type>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate {
        val lambda = modules[signatureString.modName]?.getLambda(signatureString)!!
        val returnType = lambda.inferTypes(argTypes, lookup, history)

        return SimpleFunctionCandidate(
            listOf(Type.Lambda(signatureString)) + argTypes,
            argTypes,
            returnType,
            returnType,
            Opcodes.INVOKEVIRTUAL,
            signatureString,
            "invoke",
            obfuscateName = true,
            requireDispatch = false
        )
    }

    private fun getStruct(signature: SignatureString) = modules[signature.modName]?.structs?.get(signature.structName)

    override suspend fun lookupModifiers(structName: Type): Modifiers = when (structName) {
        is Type.JvmType -> getStruct(structName.signature)?.modifiers ?: error("Cannot find struct $structName")
        else -> Modifiers.Empty
    }

    override fun lookupOrderedFields(structName: SignatureString): List<Pair<String, TemplatedType>> {
        return getStruct(structName)?.fields?.toList() ?: error("Cannot find struct $structName")
    }

    override suspend fun lookupStructGenericModifiers(structSig: SignatureString): Map<String, Modifiers> {
        return getStruct(structSig)?.generics?.lazyTransform { _, it -> it.modifiers }
            ?: error("Cannot find struct $structSig")
    }

    override suspend fun findExtensionFunction(
        instance: Type,
        funcName: String,
        lookup: IRLookup
    ): Pair<IRFunction, Map<String, Type>> {
        for ((template, blocks) in allowedImplBlocks) {
            val generics = mutableMapOf<String, Type>()
            blocks.forEach { impl ->
                if (funcName in impl.methods && template.matchesImpl(instance, generics, impl.genericModifiers, lookup)) {
                    val func = impl.methods[funcName]!! to generics
                    return func
                }
            }

        }
        error("No extension method found fpr $instance.$funcName")
    }

    override suspend fun findFunction(modName: SignatureString, funcName: String, lookup: IRLookup): IRFunction {
        modules[modName]?.functions?.get(funcName)?.let { return it }
        for ((template, blocks) in allowedImplBlocks) {
            if (template !is TemplatedType.Complex || template.signatureString == modName) { continue }
            val impl = blocks.find { funcName in it.associatedFunctions } ?: continue
            val func = impl.associatedFunctions[funcName]!!
            return func

        }
        error("No function found for $modName.$funcName")
    }
}


suspend fun IRStruct.getDefaultVariant(lookup: IRLookup): Map<String, Type> {
    kotlin.runCatching {
        val defaultGenerics = generics.mapValues { _ -> Type.Object }
        return defaultVariant ?: fields
            .mapValues { (_, tp) -> with(lookup) { tp.populate(defaultGenerics) } }
            .also { setDefaultVariant(it) }
    }.getOrElse { it.printStackTrace(); throw it }
}
