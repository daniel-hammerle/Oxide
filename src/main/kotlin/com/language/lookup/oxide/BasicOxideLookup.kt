package com.language.lookup.oxide

import com.language.TemplatedType
import com.language.compilation.*
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.templatedType.matchesImpl
import com.language.compilation.templatedType.matchesSubset
import com.language.compilation.tracking.BasicInstanceForge
import com.language.compilation.tracking.BroadForge
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.StructInstanceForge
import com.language.lookup.IRLookup
import com.language.lookup.jvm.rep.asLazyTypeMap
import com.language.lookup.jvm.rep.defaultVariant
import org.objectweb.asm.Opcodes
import kotlin.math.sign

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
        args: List<InstanceForge>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate? {
        val mod = modules[module] ?: return null
        val func = mod.functions[funcName] ?: return null
        val (returnType, id) =
            (func as BasicIRFunction).inferTypes(
                args,
                lookup,
                emptyMap(),
                history
            )

        val argTypes = args.map { it.type}
        return SimpleFunctionCandidate(
            oxideArgs = argTypes,
            jvmArgs =argTypes.map { it.toActualJvmType() },
            jvmReturnType = returnType.type.toActualJvmType(),
            oxideReturnType = returnType.type,
            returnForge = returnType,
            invocationType = Opcodes.INVOKESTATIC,
            jvmOwner = module,
            name = funcName,
            jvmName = "${funcName}_$id",
            obfuscateName = true,
            requireDispatch = false
        )
    }

    override suspend fun lookupFunctionUnknown(
        module: SignatureString,
        funcName: String,
        args: List<BroadForge>,
        lookup: IRLookup,
        history: History
    ): BroadForge {
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
        instance: InstanceForge,
        funcName: String,
        args: List<InstanceForge>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate? {
        val (func, generics, impl) = findExtensionMethod(instance.type, funcName, lookup) ?: return null

        val fullArgs =listOf(instance) + args
        val (returnType, id) = func.inferTypes(
            fullArgs,
            lookup,
            generics,
            history
        )

        return SimpleFunctionCandidate(
            oxideArgs = fullArgs.map { it.type },
            jvmArgs = fullArgs.map { it.type.toActualJvmType() },
            jvmReturnType = returnType.type.toActualJvmType(),
            oxideReturnType = returnType.type,
            Opcodes.INVOKESTATIC,
            impl.fullSignature,
            funcName,
            "${funcName}_$id",
            returnForge = returnType,
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
        instance: InstanceForge,
        funcName: String,
        args: List<BroadForge>,
        lookup: IRLookup,
        history: History
    ): BroadForge {
        val (func, generics) = findExtensionMethod(instance.type, funcName, lookup)!!

        return (func as BasicIRFunction).inferUnknown(listOf(instance) + args, lookup, generics, history)
    }

    override suspend fun lookupAssociatedExtensionFunction(
        structName: SignatureString,
        funcName: String,
        args: List<InstanceForge>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate {
        val impl = allowedImplBlocks
            .asSequence()
            .filter {(template, _) -> template !is TemplatedType.Complex || template.signatureString == structName }
            .firstNotNullOf { (_, blocks) -> blocks.find { funcName in it.associatedFunctions }  }

        val func = impl.associatedFunctions[funcName]!!
        val (returnType, id) = (func as BasicIRFunction).inferTypes(args, lookup, emptyMap(), history)

        val argTypes = args.map { it.type }
        return SimpleFunctionCandidate(
            oxideArgs = argTypes,
            jvmArgs = argTypes.map { it.toActualJvmType() },
            jvmReturnType = returnType.type.toActualJvmType(),
            oxideReturnType = returnType.type,
            Opcodes.INVOKESTATIC,
            impl.fullSignature,
            returnForge = returnType,
            name = funcName,
            jvmName = "${funcName}_$id",
            obfuscateName = true,
            requireDispatch = false
        )
    }

    override suspend fun lookupAssociatedExtensionFunctionUnknown(
        structName: SignatureString,
        funcName: String,
        args: List<BroadForge>,
        lookup: IRLookup,
        history: History
    ): BroadForge {
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
        args: List<InstanceForge>,
        lookup: IRLookup
    ): FunctionCandidate? {
        val struct = getStruct(structName) ?: return null
        val fields = struct.getDefaultVariant(lookup)
        if (fields.size != args.size) error("Invalid arg count")


        val generics = mutableMapOf<String, Type>()
        val argTypes = args.map { it.type }
        val result = struct.fields
            .zip(argTypes)
            .all { (field, tp) -> field.second.matchesSubset(tp, generics, struct.generics, lookup) }

        if (!result) {
            error("Types did not match")
        }

        val members = struct.fields.zip(args).associate { (it, forge) -> it.first to forge  }

        val genericArgMap = struct.fields.associateNotNull { (name, type) ->
            (type as? TemplatedType.Generic)?.let { name to it.name}
        }

        val forge = StructInstanceForge(members.toMutableMap(), genericArgMap, structName)

        return SimpleFunctionCandidate(
            oxideArgs = argTypes,
            jvmArgs = fields.values.toList(),
            jvmReturnType = Type.Nothing,
            oxideReturnType = Type.BasicJvmType(structName, generics.mapValues { it.value }),
            invocationType = Opcodes.INVOKESPECIAL,
            structName,
            "<init>",
            "<init>",
            obfuscateName = false,
            returnForge = forge,
            requireDispatch = false
        )
    }

    override suspend fun lookupConstructorUnknown(
        structName: SignatureString,
        args: List<BroadForge>,
        lookup: IRLookup
    ): BroadForge {
        val struct = getStruct(structName) ?: error("Struct not found")
        val fields = struct.getDefaultVariant(lookup)
        if (fields.size != args.size) error("Invalid arg count")


        val generics = mutableMapOf<String, Type>()
        val argTypes = args.map { (it as? InstanceForge)?.type ?: return BroadForge.Empty }

        val result = struct.fields
            .zip(argTypes)
            .all { (field, tp) -> field.second.matchesSubset(tp, generics, struct.generics, lookup) }

        if (!result) {
            error("Types did not match")
        }

        val members = struct.fields.zip(args).associate { (it, forge) -> it.first to forge as InstanceForge  }

        val genericArgMap = struct.fields.associateNotNull { (name, type) ->
            (type as? TemplatedType.Generic)?.let { name to it.name}
        }

        val forge = StructInstanceForge(members.toMutableMap(), genericArgMap, structName)

        return forge
    }

    override suspend fun lookupMemberField(instance: Type, name: String, lookup: IRLookup): Type {
        return when (instance) {
            is Type.JvmType -> {
                val type = getStruct(instance.signature)?.fields?.find { it.first == name }?.second
                val transformedGenerics = instance.genericTypes
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
                val type = getStruct(instance.signature)?.fields?.find { it.first == name }?.second
                return type?.defaultVariant()  ?: error("No field $instance.$name")
            }

            else -> error("$instance does not have the field `$name`")
        }
    }

    override suspend fun lookupLambdaInit(signatureString: SignatureString): FunctionCandidate {
        val lambda = modules[signatureString.modName]?.getLambda(signatureString)!!
        val args = lambda.captures.values.toList()
        val tps = args.map { it.type }
        return SimpleFunctionCandidate(
            tps,
            tps,
            Type.Nothing,
            Type.Lambda(signatureString),
            Opcodes.INVOKESPECIAL,
            signatureString,
            "<init>",
            "<init>",
            obfuscateName = false,
            requireDispatch = false,
            returnForge = BasicInstanceForge(Type.Lambda(signatureString)),
        )
    }

    override suspend fun lookupLambdaInvoke(
        signatureString: SignatureString,
        argTypes: List<InstanceForge>,
        lookup: IRLookup,
        history: History
    ): FunctionCandidate {
        val lambda = modules[signatureString.modName]?.getLambda(signatureString)!!
        val (returnType, id) = lambda.inferTypes(argTypes, lookup, history)

        return SimpleFunctionCandidate(
            listOf(Type.Lambda(signatureString)) + argTypes.map { it.type },
            argTypes.map { it.type  },
            returnType.type,
            returnType.type,
            Opcodes.INVOKEVIRTUAL,
            signatureString,
            "invoke",
            "invoke_$id",
            obfuscateName = true,
            requireDispatch = false,
            returnForge = returnType,
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
            .associate { (name, tp) -> name to with(lookup) { tp.populate(defaultGenerics) } }
            .also { setDefaultVariant(it) }
    }.getOrElse { it.printStackTrace(); throw it }
}

inline fun<T, K, V> List<T>.associateNotNull(closure: (T) -> Pair<K, V>?): Map<K, V> {
    val map = HashMap<K, V>()
    for (item in this) closure(item)?.let { map[it.first] = it.second }
    return map
}