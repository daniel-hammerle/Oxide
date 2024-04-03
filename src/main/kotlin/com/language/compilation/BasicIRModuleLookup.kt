package com.language.compilation

import java.lang.reflect.Modifier

class BasicIRModuleLookup(
    override val nativeModules: Set<IRModule>,
    private val externalJars: ClassLoader
) : IRModuleLookup {
    override fun lookUpType(modName: String, funcName: String, argTypes: List<Type>): Type {
        return when(val module = nativeModules.find{ it.name == modName }) {
            is IRModule -> {
                module.functions[funcName]?.type(argTypes, this) ?: error("Function $funcName in $modName with variants $argTypes not found")
            }
            else -> {
                val clazz = externalJars.loadClass(modName.replace("::", "."))
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

    override fun lookUpType(instance: Type, funcName: String, argTypes: List<Type>): Type {
        return when (instance) {
            is Type.JvmType -> {
                //if the class doesn't exist, we simply throw
                val clazz = externalJars.loadClass(instance.signature.replace("::", "."))
                val methods = clazz.methods.filter { it.name == funcName && it.parameterCount == argTypes.size && !Modifier.isStatic(it.modifiers) }
                val method = methods.first {
                    //map through each argument and check if it works
                    argTypes
                        .mapIndexed { index, type -> it.parameterTypes[index].canBe(type) }
                        .all { it }  //if every condition is true
                }
                method.returnType.toType()
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

    override fun lookUpFieldType(instance: Type, fieldName: String): Type {
        return when (instance) {
            is Type.JvmType -> {
                //if the class doesn't exist, we simply throw
                val clazz = externalJars.loadClass(instance.signature.replace("::", "."))
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

    override fun lookUpFieldType(modName: String, fieldName: String): Type {
        val clazz = externalJars.loadClass(modName.replace("::", "."))
        val field = clazz.fields.first { it.name == fieldName && Modifier.isStatic(it.modifiers) }
        return field.type.toType()
    }

    override fun classOf(type: Type.JvmType): Class<*> {
        return externalJars.loadClass(type.signature.replace("::", "."))
    }
}


fun Class<*>.toType() = when(val value = name.replace(".", "::")) {
    "void" -> Type.Nothing
    "int" -> Type.IntT
    "double" -> Type.DoubleT
    else -> Type.JvmType(value)
}

fun Class<*>.canBe(type: Type): Boolean {
    return when(type) {
        is Type.JvmType -> this.name.replace(".", "::") == type.signature || name == "java.lang.Object"
        Type.DoubleT -> name == "double"
        Type.IntT -> name == "int"
        Type.BoolT -> name == "boolean"
        Type.Nothing -> false
        Type.Null -> true
        is Type.Union -> type.entries.any { this.canBe(it) }
    }
}