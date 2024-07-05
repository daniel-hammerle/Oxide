package com.language.lookup.oxide

import com.language.TemplatedType
import com.language.codegen.compileInstruction
import com.language.compilation.*
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.templatedType.matches
import com.language.lookup.IRLookup
import com.language.lookup.jvm.rep.asLazyTypeMap
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

    override suspend fun lookUpGenericTypes(instance: Type, funcName: String, argTypes: List<Type>, lookup: IRLookup): Map<String, Type>? {
        //this can never happen in native methods since we don't have static type annotations
        return if (hasExtensionMethod(instance, funcName, lookup)) {
            emptyMap()
        } else null
    }

    private suspend fun hasExtensionMethod(instance: Type, funcName: String, lookup: IRLookup): Boolean {
        for ((template, blocks) in allowedImplBlocks) {
            val generics = mutableMapOf<String, Type>()
            blocks.forEach { impl ->
                if (funcName in impl.methods && template.matches(instance, generics, impl.genericModifiers, lookup)) {
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
        lookup: IRLookup
    ): FunctionCandidate {
        val mod = modules[module] ?: error("No Module found with name `$module`")
        val func = mod.functions[funcName] ?: error("No Function `$funcName` on module `$module`")
        val returnType = runCatching { (func as BasicIRFunction).inferTypes(args, lookup, emptyMap()) }.getOrElse { it.printStackTrace(); throw it }

        return FunctionCandidate(
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

    override suspend fun lookupExtensionMethod(instance: Type, funcName: String, args: List<Type>, lookup: IRLookup): FunctionCandidate {
        for ((template, blocks) in allowedImplBlocks) {
            val generics = mutableMapOf<String, Type>()
            blocks.forEach { impl ->
                if (funcName in impl.methods && template.matches(instance, generics, impl.genericModifiers, lookup)) {
                    val func = impl.methods[funcName]!!
                    val returnType = runCatching { (func as BasicIRFunction).inferTypes(listOf(instance) + args, lookup, generics) }.getOrElse { it.printStackTrace(); throw it }
                    println("Method $instance.$funcName($args) returns $returnType")
                    return FunctionCandidate(
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
            }

        }
        error("No extension method found")
    }

    override suspend fun lookupAssociatedExtensionFunction(
        structName: SignatureString,
        funcName: String,
        args: List<Type>,
        lookup: IRLookup
    ): FunctionCandidate {
        for ((template, blocks) in allowedImplBlocks) {
            if (template is TemplatedType.Complex && template.signatureString == structName) {
                val impl = blocks.find { funcName in it.associatedFunctions }
                if (impl != null) {
                    val func = impl.associatedFunctions[funcName]!!
                    val returnType = (func as BasicIRFunction).inferTypes(args, lookup, emptyMap())
                    return FunctionCandidate(
                        oxideArgs = args,
                        jvmArgs = args.map { it.toActualJvmType() },
                        jvmReturnType = returnType.toActualJvmType(),
                        oxideReturnType = returnType,
                        Opcodes.INVOKESPECIAL,
                        impl.fullSignature,
                        name = funcName,
                        obfuscateName = true,
                        requireDispatch = false
                    )
                }
            }
        }
        error("No associated function found method found")
    }

    override suspend fun lookupConstructor(structName: SignatureString, args: List<Type>, lookup: IRLookup): FunctionCandidate {
        val struct = getStruct(structName) ?: error("Cannot find struct `$structName`")
        val fields = struct.getDefaultVariant(lookup)
        if (fields.size != args.size) error("Invalid arg count")


        val generics = mutableMapOf<String, Type>()

        val result = struct
            .fields
            .toList()
            .zip(args)
            .all { (field, tp) -> field.second.matches(tp, generics, struct.generics, lookup) }

        if (!result) error("Types did not match")

        return FunctionCandidate(
            oxideArgs = args,
            jvmArgs = fields.values.toList(),
            jvmReturnType = Type.Nothing,
            oxideReturnType = Type.BasicJvmType(structName, generics.mapValues { Type.BroadType.Known(it.value) }),
            invocationType = Opcodes.INVOKESPECIAL,
            structName,
            "<init>",
            obfuscateName = false,
            requireDispatch = false
        )
    }

    override suspend fun lookupMemberField(instance: Type, name: String, lookup: IRLookup): Type {
        return when(instance) {
            is Type.JvmType -> {
                val type = getStruct(instance.signature)?.fields?.get(name)
                val transformedGenerics = instance.genericTypes.asLazyTypeMap()
                with(lookup) { type?.populate(transformedGenerics) } ?: error("No field $instance.$name")
            }
            else -> error("$instance does not have the field `$name`")
        }
    }

    override suspend fun lookupLambdaInit(signatureString: SignatureString): FunctionCandidate {
        val lambda = modules[signatureString.modName]?.getLambda(signatureString)!!
        val args = lambda.captures.values.toList()
        return FunctionCandidate(
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

    override suspend fun lookupLambdaInvoke(signatureString: SignatureString, argTypes: List<Type>, lookup: IRLookup): FunctionCandidate {
        val lambda = modules[signatureString.modName]?.getLambda(signatureString)!!
        val returnType = lambda.inferTypes(argTypes, lookup)

        return FunctionCandidate(
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

    override suspend fun lookupModifiers(structName: Type): Modifiers = when(structName) {
        is Type.JvmType ->getStruct(structName.signature)?.modifiers ?: error("Cannot find struct $structName")
        else -> Modifiers.Empty
    }

    override fun lookupOrderedFields(structName: SignatureString): List<Pair<String, TemplatedType>> {
        return getStruct(structName)?.fields?.toList() ?: error("Cannot find struct $structName")
    }

    override suspend fun lookupStructGenericModifiers(structSig: SignatureString): Map<String, Modifiers> {
        return getStruct(structSig)?.generics?.lazyTransform { _, it -> it.modifiers } ?: error("Cannot find struct $structSig")
    }

    override suspend fun findExtensionFunction(instance: Type, funcName: String, lookup: IRLookup): IRFunction {
        for ((template, blocks) in allowedImplBlocks) {
            val generics = mutableMapOf<String, Type>()
            blocks.forEach { impl ->
                if (funcName in impl.methods && template.matches(instance, generics, impl.genericModifiers, lookup)) {
                    val func = impl.methods[funcName]!!
                    return func
                }
            }

        }
        error("No extension method found fpr $instance.$funcName")
    }

    override suspend fun findFunction(modName: SignatureString, funcName: String, lookup: IRLookup): IRFunction {
        modules[modName]?.functions?.get(funcName)?.let { return it }
        for ((template, blocks) in allowedImplBlocks) {
            if (template is TemplatedType.Complex && template.signatureString == modName) {
                val impl = blocks.find { funcName in it.associatedFunctions }
                if (impl != null) {
                    val func = impl.associatedFunctions[funcName]!!
                    return func
                }
            }
        }
        error("No function found for $modName.$funcName")
    }
}


suspend fun IRStruct.getDefaultVariant(lookup: IRLookup): Map<String, Type> {
    kotlin.runCatching {
        val defaultGenerics = generics.mapValues { _ -> Type.Object  }
        return defaultVariant ?: fields
            .mapValues { (_, tp) -> with(lookup) { tp.populate(defaultGenerics) } }
            .also { setDefaultVariant(it) }
    }.getOrElse { it.printStackTrace(); throw it }
}
