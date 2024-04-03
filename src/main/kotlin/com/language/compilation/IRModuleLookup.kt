package com.language.compilation

interface IRModuleLookup {
    val nativeModules: Set<IRModule>
    fun lookUpType(modName: String, funcName: String, argTypes: List<Type>): Type
    fun lookUpType(instance: Type, funcName: String, argTypes: List<Type>): Type
    fun lookUpFieldType(instance: Type, fieldName: String): Type
    fun lookUpFieldType(modName: String, fieldName: String): Type
    fun classOf(type: Type.JvmType): Class<*>
}