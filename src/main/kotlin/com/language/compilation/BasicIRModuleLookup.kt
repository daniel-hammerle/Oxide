package com.language.compilation

import com.language.TemplatedType
import com.language.codegen.toJVMDescriptor
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.templatedType.matches
import com.language.lookup.IRLookup
import org.objectweb.asm.Opcodes
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class BasicIRModuleLookup(
    val nativeModules: Set<IRModule>,
    private val externalJars: ClassLoader,
    private val allowedImplBlocks: Map<TemplatedType, Set<IRImpl>> = emptyMap()
) : IRLookup {


    private suspend fun getExtensionFunction(instance: Type, name: String): Triple<IRImpl, IRFunction, Map<String, Type>>? {
        for ((template, blocks) in allowedImplBlocks) {
            val generics = mutableMapOf<String, Type>()
            blocks.forEach { impl ->
                if (name in impl.methods && template.matches(instance, generics, impl.genericModifiers, this)) {
                    return Triple(impl, impl.methods[name]!!, generics)
                }
            }

        }
        return null
    }

    private fun getAssociatedFunction(instance: SignatureString, name: String): Pair<IRImpl, IRFunction>? {
        for ((template, blocks) in allowedImplBlocks) {
            if (template is TemplatedType.Complex && template.signatureString == instance) {
                val impl = blocks.find { name in it.associatedFunctions }
                if (impl != null) {
                    return impl to impl.associatedFunctions[name]!!
                }
            }
        }
        return null
    }

    override suspend fun hasModifier(instance: Type, modifier: com.language.compilation.modifiers.Modifier): Boolean {
        return when(instance) {
            is Type.JvmType -> {
                val struct = getStruct(instance.signature)
                struct?.modifiers?.isModifier(modifier) ?: false
            }
            else -> false
        }
    }

    override suspend fun satisfiesModifiers(instance: Type, modifiers: Modifiers): Boolean {
        return when(instance) {
            is Type.JvmType -> {
                val struct = getStruct(instance.signature)
                struct?.modifiers == modifiers
            }
            else -> false
        }
    }

    override fun newModFrame(modNames: Set<SignatureString>): IRLookup {
        val newImplBlocks: MutableMap<TemplatedType, MutableSet<IRImpl>> = mutableMapOf()
        nativeModules.filter { it.name in modNames }.forEach { mod ->
            mod.implBlocks.forEach { (type, block) ->
                newImplBlocks[type]?.addAll(block) ?: newImplBlocks.put(type, block.toMutableSet())
            }
        }

        return BasicIRModuleLookup(nativeModules, externalJars, newImplBlocks)
    }

    override suspend fun lookUpCandidate(modName: SignatureString, funcName: String, argTypes: List<Type>): FunctionCandidate {
        if (nativeModules.any{ it.name == modName }) {
            val module = nativeModules.first{ it.name == modName }
            return module.functions[funcName]?.inferTypes(argTypes, this, emptyMap())?.let {
                    FunctionCandidate(
                    argTypes,
                    argTypes,
                    it,
                    it,
                    invocationType = Opcodes.INVOKESTATIC,
                    jvmOwner = modName,
                    name = funcName,
                    true,
                        requireDispatch = false
                )
            } ?: error("Function $funcName in $modName with variants $argTypes not found")
        }
        when (val result = getAssociatedFunction(modName, funcName)) {
            is Pair<IRImpl, IRFunction> -> {
                val (implBlock, function) = result
                return function.inferTypes(argTypes, lookup = this, emptyMap()).let {
                    FunctionCandidate(
                        argTypes,
                        argTypes.map { t -> t.toActualJvmType() },
                        it.toActualJvmType(),
                        it,
                        invocationType = Opcodes.INVOKESTATIC,
                        jvmOwner = implBlock.fullSignature,
                        name = funcName,
                        obfuscateName = true,
                        requireDispatch = false
                    )
                }
            }


        }
        val clazz = externalJars.loadClass(modName.toDotNotation())
        val methods = clazz.methods.filter {
            it.name == funcName && it.parameterCount == argTypes.size && Modifier.isStatic(it.modifiers)
        }
        val method = methods.firstOrNull() {
            //map through each argument and check if it works
            argTypes
                .mapIndexed { index, type -> it.parameterTypes[index].canBe(type) }
                .all { it }  //if every condition is true
        } ?: error("No methods for $modName.$funcName")
        val returnType = method.returnType.toType()
        val actualArgTypes = method.parameterTypes.map { it.toType() }
        //oxide and jvm return types are the same since generics cant exist on static functions
        return FunctionCandidate(
            oxideArgs = argTypes,
            jvmArgs = actualArgTypes,
            jvmReturnType = returnType,
            oxideReturnType = returnType,
            invocationType = Opcodes.INVOKESTATIC,
            jvmOwner = modName,
            name = funcName,
            obfuscateName = false,
            requireDispatch = false
        )
    }

    override suspend fun typeHasInterface(type: Type, interfaceType: SignatureString): Boolean {
        return when(type) {
            is Type.JvmType -> {
                if (getStruct(type.signature) != null) {
                    //native structs do not support jvm interfaces;
                    //therefore, it is false
                    return false
                }
                val clazz = externalJars.loadClass(type.signature.toDotNotation())
                clazz.interfaces.any { it.name == interfaceType.toDotNotation() || typeHasInterface(Type.BasicJvmType(SignatureString.fromDotNotation(it.name), linkedMapOf()), interfaceType) }
            }
            else -> false
        }
    }

    override suspend fun lookUpGenericTypes(instance: Type, funcName: String, argTypes: List<Type>): Map<String, Int> {
        when(instance) {
            is Type.JvmType -> {
                if (getStruct(instance.signature) != null) {
                    return emptyMap()
                }
                //if we have impl blocks we dont have generic changes for now:
                if (getExtensionFunction(instance, funcName) != null) {
                    return emptyMap()
                }

                //if the class doesn't exist, we simply throw
                val method = loadMethod(instance.signature, funcName, argTypes)

                val typeParameters = method.genericParameterTypes
                val typeMap =  typeParameters
                    .mapIndexed { index, c -> index to c  }
                    .filter { it.second.typeName in instance.genericTypes }
                    .associate { (index, c) -> c.typeName to index }
                return typeMap
            }
            else -> return emptyMap()
        }
    }


    private fun loadMethod(signatureString: SignatureString, funcName: String, argTypes: List<Type>): Method {
        val clazz = externalJars.loadClass(signatureString.toDotNotation())
        val methods = clazz.methods.filter { it.name == funcName && it.parameterCount == argTypes.size && !Modifier.isStatic(it.modifiers) }
        val method = methods.firstOrNull {
            //map through each argument and check if it works
            argTypes
                .mapIndexed { index, type -> it.parameterTypes[index].canBe(type) }
                .all { it }  //if every condition is true
        } ?: methods.firstOrNull {
            //map through each argument and check if it works
            argTypes
                .mapIndexed { index, type -> it.parameterTypes[index].canBe(type.asBoxed()) }
                .all { it }  //if every condition is true
        } ?: error("No matching method found $signatureString::$funcName($argTypes)")
        return method
    }

    override suspend fun hasGenericReturnType(instance: Type, funcName: String, argTypes: List<Type>): Boolean {
        return when(instance) {
            is Type.JvmType -> {
                val method = loadMethod(instance.signature, funcName, argTypes)
                method.returnType.name != method.genericReturnType.typeName
            }
            else -> false
        }
    }

    private fun Type.ungenerify(): Type = when(this) {
        is Type.JvmType -> Type.BasicJvmType(signature, linkedMapOf())
        is Type.Union -> Type.Union(entries.map { it.ungenerify() }.toSet())
        else -> this
    }

    override suspend fun lookUpCandidate(instance: Type, funcName: String, argTypes: List<Type>): FunctionCandidate {
        when(val result = getExtensionFunction(instance, funcName)) {
            is Triple<IRImpl, IRFunction, Map<String, Type>> -> {
                val (implBlock, function, generics) = result
                return function.inferTypes(listOf(instance) + argTypes, lookup = this, generics).let {
                    FunctionCandidate(
                        listOf(instance) + argTypes,
                        listOf(instance) + argTypes,
                        it.toActualJvmType(),
                        it,
                        invocationType = Opcodes.INVOKESTATIC,
                        jvmOwner = implBlock.fullSignature,
                        name = funcName,
                        obfuscateName = true,
                        requireDispatch = false
                    )
                }
            }
        }
        return when (instance) {
            is Type.JvmType -> {
                //if the class doesn't exist, we simply throw
                val method = loadMethod(instance.signature, funcName, argTypes)

                val returnType = when (val tp = instance.genericTypes[method.genericReturnType.typeName]) {
                    is Type.BroadType.Unset -> Type.Object
                    is Type.BroadType.Known -> tp.type
                    else -> method.returnType.toType()
                }

                val actualArgTypes = method.parameterTypes.map { it.toType() }

                val jvmReturnType = when {
                    method.genericReturnType.typeName in instance.genericTypes -> Type.Object
                    else -> returnType
                }

                FunctionCandidate(
                    oxideArgs = listOf(instance) + argTypes,
                    jvmArgs = actualArgTypes,
                    oxideReturnType = returnType,
                    jvmReturnType = jvmReturnType,
                    invocationType = if (classOf(instance).isInterface) Opcodes.INVOKEINTERFACE else Opcodes.INVOKEVIRTUAL,
                    jvmOwner = instance.signature,
                    name = funcName,
                    obfuscateName = false,
                    requireDispatch = false
                )
            }
            is Type.BoolT -> lookUpCandidate(Type.Bool, funcName, argTypes)
            Type.DoubleT -> lookUpCandidate(Type.Double, funcName, argTypes)
            Type.IntT -> lookUpCandidate(Type.Int, funcName, argTypes)
            Type.Nothing -> error("Nothing does not have function $funcName")
            Type.Never -> error("Type never does not have function $funcName (or any functions)")
            Type.Null -> error("Null does not have function $funcName")
            is Type.Union -> {
                val types = instance.entries.map { lookUpCandidate(it, funcName, argTypes) }
                types[0].copy(requireDispatch = true)
            }
            is Type.Array -> TODO()
        }
    }


    private fun getStruct(structPath: SignatureString): IRStruct? {
        return nativeModules.find { it.name == structPath.modName}?.structs?.get(structPath.structName)
    }

    override suspend fun lookUpConstructor(className: SignatureString, argTypes: List<Type>): FunctionCandidate {
        when(val struct = getStruct(className)) {
            is IRStruct -> {
                if (struct.fields.size != argTypes.size) {
                    error("No constructor $className($argTypes)")
                }
                val baseGenerics = linkedMapOf(*struct.generics.map { it.key to Type.Object as Type }.toTypedArray())
                val typedFields = struct.fields.mapValues { (_, value) -> value.populate(baseGenerics) }

                if (struct.defaultVariant == null) struct.setDefaultVariant(typedFields)

                typedFields.values
                    .zip(argTypes)
                    .forEach { (fieldType, argType) -> argType.assertIsInstanceOf(fieldType)
                }
                return FunctionCandidate(
                    oxideArgs =typedFields.values.toList(),
                    jvmArgs = typedFields.values.toList(),
                    jvmReturnType = Type.Nothing,
                    oxideReturnType = Type.BasicJvmType(className),
                    invocationType = Opcodes.INVOKESPECIAL,
                    jvmOwner = className,
                    name = "<init>",
                    obfuscateName = false,
                    requireDispatch = false
                )
            }
        }

        val clazz = classOf(Type.BasicJvmType(className))
        val constructor = clazz.constructors.firstOrNull { constructor ->
            constructor.parameterCount == argTypes.size &&
            constructor.parameterTypes
                .zip(argTypes)
                .all{ (pType, argType) -> pType.canBe(argType) }
        }
        when(constructor) {
            is Constructor<*> -> return FunctionCandidate(
                argTypes,
                constructor.parameterTypes.map { it.toType() },
                Type.Nothing,
                clazz.toType(),
                invocationType = Opcodes.INVOKESPECIAL,
                jvmOwner = className,
                name = "<init>",
                obfuscateName = false,
                requireDispatch = false
            )
            else -> error("No constructor $className($argTypes)")
        }
    }


    override fun lookUpOrderedFields(className: SignatureString): List<Pair<String, TemplatedType>> {
        return when(val module = nativeModules.find{ it.name == className.modName }) {
            is IRModule -> {
                val struct = module.structs[className.structName] ?: error("Cannot Find struct with $className")
                struct.fields.toList()
            }

            else -> error("Ordered Fields are only guaranteed for `oxide` types ($className)")
        }
    }

    override suspend fun lookUpFieldType(instance: Type, fieldName: String): Type {
        return when (instance) {
            is Type.JvmType -> {
                when (val type = nativeModules.find { it.name == instance.signature.modName }?.structs?.get(instance.signature.structName)?.fields?.get(fieldName) ) {
                    is TemplatedType -> return type.populate(instance.genericTypes.mapValues { it.value.toType() } as LinkedHashMap)
                    else -> {}
                }

                //if the class doesn't exist, we simply throw
                val clazz = externalJars.loadClass(instance.signature.toDotNotation())
                val field = clazz.fields.firstOrNull { it.name == fieldName && !Modifier.isStatic(it.modifiers) } ?: error("No field: $instance, $fieldName")
                field.type.toType()
            }
            is Type.BoolT -> error("Pimitive bool does not have field $fieldName")
            Type.DoubleT -> error("Primitive double does not have field $fieldName")
            Type.IntT -> error("Primitive int does not have field $fieldName")
            Type.Nothing -> error("Nothing does not have field $fieldName")
            Type.Never -> error("Never does not have field $fieldName")
            Type.Null -> error("Null does not have field $fieldName")
            is Type.Union -> {
                val types = instance.entries.map { lookUpFieldType(it, fieldName) }
                types.reduce { acc, type -> acc.join(type) }
            }

            is Type.Array -> TODO()
        }
    }

    private fun Type.BroadType.toType() = when(this) {
        is Type.BroadType.Known -> type
        else -> error("Not eonough information to know type!")
    }

    private fun signatureGetGenerics(signatureString: SignatureString): Map<String, GenericType> {
        when(val struct = getStruct(signatureString)) {
            is IRStruct -> {
                return struct.generics
            }
        }
        return externalJars.loadClass(signatureString.toDotNotation()).typeParameters.associate { it.name  to GenericType(Modifiers.Empty, emptyList())}
    }

    override suspend fun lookUpFieldType(modName: SignatureString, fieldName: String): Type {
        val clazz = externalJars.loadClass(modName.toDotNotation())
        val field = clazz.fields.first { it.name == fieldName && Modifier.isStatic(it.modifiers) }
        return field.type.toType()
    }

    fun classOf(type: Type.JvmType): Class<*> {
        return externalJars.loadClass(type.signature.toDotNotation())
    }

    override suspend fun TemplatedType.populate(generics: Map<String, Type>): Type = when(this) {
        is TemplatedType.Array -> Type.Array(Type.BroadType.Known(itemType.populate(generics)))
        is TemplatedType.Complex -> {
            val availableGenerics = signatureGetGenerics(signatureString)
            val entries = availableGenerics.toList().mapIndexed { index, s ->
                s.first to Type.BroadType.Known(this.generics[index].populate(generics))
            }.toTypedArray()
            Type.BasicJvmType(signatureString, linkedMapOf(*entries))
        }
        is TemplatedType.Nothing -> Type.Nothing
        is TemplatedType.Generic -> generics[name]!!
        TemplatedType.IntT -> Type.IntT
        TemplatedType.DoubleT -> Type.DoubleT
        TemplatedType.BoolT -> Type.BoolUnknown
        TemplatedType.Null -> Type.Null
        is TemplatedType.Union -> Type.Union(types.map { it.populate(generics) }.toSet())
    }

}


fun Class<*>.toType(): Type {
    return when(name) {
        "void" -> Type.Nothing
        "int" -> Type.IntT
        "double" -> Type.DoubleT
        "boolean" -> Type.BoolUnknown
        else -> {
            if (name.startsWith("[")) {
                return Type.Array(Type.BroadType.Known(Class.forName(name.removePrefix("[L").removeSuffix(";")).toType()))
            }
            val generics = linkedMapOf(*typeParameters.map { it.typeName to Type.BroadType.Unset as Type.BroadType }.toTypedArray())
            Type.BasicJvmType(SignatureString.fromDotNotation(name), generics)
        }
    }
}


fun Class<*>.canBe(type: Type, strict: Boolean = false, nullable: Boolean = false): Boolean {
    if (!strict && (name == "double" || name == "java.lang.Double") && (type == Type.IntT || type == Type.Int)) return true
    if (name == "java.lang.Object") return true
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
        is Type.Array -> name == type.toJVMDescriptor().replace("/", ".")
        Type.Never -> false
    }
}