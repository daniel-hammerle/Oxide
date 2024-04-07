package com.language.compilation

import com.language.codegen.generateJVMFunctionSignature
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class BasicIRModuleLookup(
    override val nativeModules: Set<IRModule>,
    private val externalJars: ClassLoader
) : IRModuleLookup {
    override fun lookUpType(modName: SignatureString, funcName: String, argTypes: List<Type>): Type {
        return when(val module = nativeModules.find{ it.name == modName }) {
            is IRModule -> {
                module.functions[funcName]?.type(argTypes, this) ?: error("Function $funcName in $modName with variants $argTypes not found")
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
                method.returnType.toType()
            }

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
        }
    }

    private fun loadMethod(signatureString: SignatureString, funcName: String, argTypes: List<Type>): Method {
        val clazz = externalJars.loadClass(signatureString.toDotNotation())
        val methods = clazz.methods.filter { it.name == funcName && it.parameterCount == argTypes.size && !Modifier.isStatic(it.modifiers) }
        val method = methods.first {
            //map through each argument and check if it works
            argTypes
                .mapIndexed { index, type -> it.parameterTypes[index].canBe(type) }
                .all { it }  //if every condition is true
        }
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

    override fun lookUpType(instance: Type, funcName: String, argTypes: List<Type>): Type {
        return when (instance) {
            is Type.JvmType -> {
                //if the class doesn't exist, we simply throw
                val method = loadMethod(instance.signature, funcName, argTypes)

                when (val tp = instance.genericTypes[method.genericReturnType.typeName]) {
                    is Type.BroadType.Unknown -> Type.Object
                    is Type.BroadType.Known -> tp.type
                    else -> method.returnType.toType()
                }
            }
            Type.BoolT -> lookUpType(Type.Bool, funcName, argTypes)
            Type.DoubleT -> lookUpType(Type.Double, funcName, argTypes)
            Type.IntT -> lookUpType(Type.Int, funcName, argTypes)
            Type.Nothing -> error("Nothing does not have function $funcName")
            Type.Null -> error("Null does not have function $funcName")
            is Type.Union -> {
                val types = instance.entries.map { lookUpType(it, funcName, argTypes) }
                types.reduce { acc, type -> acc.join(type) }
            }
        }
    }


    private fun getStruct(structPath: SignatureString): IRStruct? {
        return nativeModules.find { it.name == structPath.modName}?.structs?.get(structPath.structName)
    }

    override fun lookUpConstructor(className: SignatureString, argTypes: List<Type>): Type {
        when(val struct = getStruct(className)) {
            is IRStruct -> {
                if (struct.fields.size != argTypes.size) {
                    error("No constructor $className($argTypes)")
                }
                struct.fields.values
                    .zip(argTypes)
                    .forEach { (fieldType, argType) -> argType.assertIsInstanceOf(fieldType) }
                return Type.BasicJvmType(className)
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
            is Constructor<*> -> return clazz.toType()
            else -> error("No constructor $className($argTypes)")
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
                val field = clazz.fields.first { it.name == fieldName && !Modifier.isStatic(it.modifiers) }
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


fun Class<*>.toType() = when(name) {
    "void" -> Type.Nothing
    "int" -> Type.IntT
    "double" -> Type.DoubleT
    "boolean" -> Type.BoolT
    else -> {
        val generics = typeParameters.associate { it.typeName to Type.BroadType.Unknown }
        Type.BasicJvmType(SignatureString.fromDotNotation(name), generics)
    }
}

fun Class<*>.canBe(type: Type): Boolean {
    if (name == "java.lang.Object" && !type.isUnboxedPrimitive()) return true
    return when(type) {
        is Type.JvmType -> SignatureString(this.name.replace(".", "::")) == type.signature || name == "java.lang.Object"
        Type.DoubleT -> name == "double"
        Type.IntT -> name == "int"
        Type.BoolT -> name == "boolean"
        Type.Nothing -> false
        Type.Null -> true
        is Type.Union -> type.entries.any { this.canBe(it) }
    }
}