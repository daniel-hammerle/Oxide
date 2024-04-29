package com.language.compilation

import com.language.codegen.generateJVMFunctionSignature
import com.language.codegen.toJVMDescriptor
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class BasicIRModuleLookup(
    override val nativeModules: Set<IRModule>,
    private val externalJars: ClassLoader
) : IRModuleLookup {
    override suspend fun lookUpCandidate(modName: SignatureString, funcName: String, argTypes: List<Type>): FunctionCandidate {
        return when(val module = nativeModules.find{ it.name == modName }) {
            is IRModule -> {
                module.functions[funcName]?.inferTypes(argTypes, this)?.let { FunctionCandidate(argTypes, argTypes, it.type, it.type) } ?: error("Function $funcName in $modName with variants $argTypes not found")
            }
            else -> {
                val clazz = externalJars.loadClass(modName.toDotNotation())
                val methods = clazz.methods.filter {
                    it.name == funcName && it.parameterCount == argTypes.size && Modifier.isStatic(it.modifiers)
                }
                val method = methods.first {
                    //map through each argument and check if it works
                    argTypes
                        .mapIndexed { index, type -> it.parameterTypes[index].canBe(type) }
                        .all { it }  //if every condition is true
                }
                val returnType = method.returnType.toType()
                val actualArgTypes = method.parameterTypes.map { it.toType() }
                //oxide and jvm return types are the same since generics cant exist on static functions
                FunctionCandidate(oxideArgs = argTypes, jvmArgs = actualArgTypes, jvmReturnType = returnType, oxideReturnType = returnType)
            }

        }
    }

    override fun typeIsInterface(type: Type, interfaceType: SignatureString): Boolean {
        return when(type) {
            is Type.JvmType -> {
                if (getStruct(type.signature) != null) {
                    //native structs do not support jvm interfaces;
                    //therefore, it is false
                    return false
                }
                val clazz = externalJars.loadClass(type.signature.toDotNotation())
                clazz.interfaces.any { it.name == interfaceType.toDotNotation() || typeIsInterface(Type.BasicJvmType(SignatureString.fromDotNotation(it.name), emptyMap()), interfaceType) }
            }
            else -> false
        }
    }

    override fun lookUpGenericTypes(instance: Type, funcName: String, argTypes: List<Type>): Map<String, Int> {
        when(instance) {
            is Type.JvmType -> {
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

    override fun generateCallSignature(instance: Type, funcName: String, argTypes: List<Type>): String {
        return when (instance) {
            is Type.JvmType -> {
                //if the class doesn't exist, we simply throw
                val method = loadMethod(instance.signature, funcName, argTypes)

                generateJVMFunctionSignature(method.parameterTypes.map { it.toType() }, returnType = method.returnType.toType())
            }
            Type.BoolT -> generateCallSignature(Type.Bool, funcName, argTypes)
            Type.DoubleT -> generateCallSignature(Type.Double, funcName, argTypes)
            Type.IntT -> generateCallSignature(Type.Int, funcName, argTypes)
            Type.Nothing -> error("Nothing does not have function $funcName")
            Type.Null -> error("Null does not have function $funcName")
            is Type.Union -> error("Can not return a single call signature for a UnionType")
            is Type.Array -> error("Cannot call methods on array types for now")
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
        } ?: error("No matching method found")
        return method
    }

    override fun hasGenericReturnType(instance: Type, funcName: String, argTypes: List<Type>): Boolean {
        return when(instance) {
            is Type.JvmType -> {
                val method = loadMethod(instance.signature, funcName, argTypes)
                method.returnType.name != method.genericReturnType.typeName
            }
            else -> false
        }
    }

    override fun lookUpCandidate(instance: Type, funcName: String, argTypes: List<Type>): FunctionCandidate {
        return when (instance) {
            is Type.JvmType -> {
                //if the class doesn't exist, we simply throw
                val method = loadMethod(instance.signature, funcName, argTypes)

                val returnType = when (val tp = instance.genericTypes[method.genericReturnType.typeName]) {
                    is Type.BroadType.Unknown -> Type.Object
                    is Type.BroadType.Known -> tp.type
                    else -> method.returnType.toType()
                }

                val actualArgTypes = method.parameterTypes.map { it.toType() }

                val jvmReturnType = when {
                    method.genericReturnType.typeName in instance.genericTypes -> Type.Object
                    else -> returnType
                }
                FunctionCandidate(
                    oxideArgs = argTypes,
                    jvmArgs = actualArgTypes,
                    oxideReturnType = returnType,
                    jvmReturnType = jvmReturnType
                )
            }
            Type.BoolT -> lookUpCandidate(Type.Bool, funcName, argTypes)
            Type.DoubleT -> lookUpCandidate(Type.Double, funcName, argTypes)
            Type.IntT -> lookUpCandidate(Type.Int, funcName, argTypes)
            Type.Nothing -> error("Nothing does not have function $funcName")
            Type.Null -> error("Null does not have function $funcName")
            is Type.Union -> {
                val types = instance.entries.map { lookUpCandidate(it, funcName, argTypes) }
                types[0]
            }

            is Type.Array -> TODO()
        }
    }


    private fun getStruct(structPath: SignatureString): IRStruct? {
        return nativeModules.find { it.name == structPath.modName}?.structs?.get(structPath.structName)
    }

    override fun lookUpConstructor(className: SignatureString, argTypes: List<Type>): FunctionCandidate {
        when(val struct = getStruct(className)) {
            is IRStruct -> {
                if (struct.fields.size != argTypes.size) {
                    error("No constructor $className($argTypes)")
                }
                struct.fields.values
                    .zip(argTypes)
                    .forEach { (fieldType, argType) -> argType.assertIsInstanceOf(fieldType) }
                return FunctionCandidate(struct.fields.values.toList(), struct.fields.values.toList(), Type.Nothing, Type.BasicJvmType(className))
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
                clazz.toType()
            )
            else -> error("No constructor $className($argTypes)")
        }
    }


    override fun lookUpOrderedFields(className: SignatureString): List<Pair<String, Type>> {
        return when(val module = nativeModules.find{ it.name == className.modName }) {
            is IRModule -> {
                val struct = module.structs[className.structName] ?: error("Cannot Find struct with $className")
                struct.fields.toList()
            }

            else -> error("Ordered Fields are only guaranteed for `oxide` types ($className)")
        }
    }

    override fun lookUpFieldType(instance: Type, fieldName: String): Type {
        return when (instance) {
            is Type.JvmType -> {
                when (val type = nativeModules.find { it.name == instance.signature.modName }?.structs?.get(instance.signature.structName)?.fields?.get(fieldName) ) {
                    is Type -> return type
                    else -> {}
                }

                //if the class doesn't exist, we simply throw
                val clazz = externalJars.loadClass(instance.signature.toDotNotation())
                val field = clazz.fields.firstOrNull { it.name == fieldName && !Modifier.isStatic(it.modifiers) } ?: error("No field: $instance, $fieldName")
                field.type.toType()
            }
            Type.BoolT -> error("Pimitive bool does not have field $fieldName")
            Type.DoubleT -> error("Primitive double does not have field $fieldName")
            Type.IntT -> error("Primitive int does not have field $fieldName")
            Type.Nothing -> error("Nothing does not have field $fieldName")
            Type.Null -> error("Null does not have field $fieldName")
            is Type.Union -> {
                val types = instance.entries.map { lookUpFieldType(it, fieldName) }
                types.reduce { acc, type -> acc.join(type) }
            }

            is Type.Array -> TODO()
        }
    }

    override fun lookUpFieldType(modName: SignatureString, fieldName: String): Type {
        val clazz = externalJars.loadClass(modName.toDotNotation())
        val field = clazz.fields.first { it.name == fieldName && Modifier.isStatic(it.modifiers) }
        return field.type.toType()
    }

    override fun classOf(type: Type.JvmType): Class<*> {
        return externalJars.loadClass(type.signature.toDotNotation())
    }

}


fun Class<*>.toType(): Type {
    return when(name) {
        "void" -> Type.Nothing
        "int" -> Type.IntT
        "double" -> Type.DoubleT
        "boolean" -> Type.BoolT
        else -> {
            if (name.startsWith("[")) {
                return Type.Array(Class.forName(name.removePrefix("[L").removeSuffix(";")).toType())
            }
            val generics = typeParameters.associate { it.typeName to Type.BroadType.Unknown }
            Type.BasicJvmType(SignatureString.fromDotNotation(name), generics)
        }
    }
}


fun Class<*>.canBe(type: Type, strict: Boolean = false): Boolean {
    if (!strict && (name == "double" || name == "java.lang.Double") && (type == Type.IntT || type == Type.Int)) return true
    if (name == "java.lang.Object" && if (strict) !type.isUnboxedPrimitive() else true) return true
    return when(type) {
        is Type.JvmType -> SignatureString(this.name.replace(".", "::")) == type.signature || name == "java.lang.Object"
        Type.DoubleT -> name == "double"
        Type.IntT -> name == "int"
        Type.BoolT -> name == "boolean"
        Type.Nothing -> false
        Type.Null -> true
        is Type.Union -> type.entries.all { this.canBe(it) }
        is Type.Array -> name == type.toJVMDescriptor().replace("/", ".")
    }
}