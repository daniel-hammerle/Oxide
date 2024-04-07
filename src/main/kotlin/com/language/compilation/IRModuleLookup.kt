package com.language.compilation

interface IRModuleLookup {
    val nativeModules: Set<IRModule>
    fun lookUpType(modName: SignatureString, funcName: String, argTypes: List<Type>): Type
    fun lookUpGenericTypes(instance: Type, funcName: String, argTypes: List<Type>): Map<String, Int>
    fun hasGenericReturnType(instance: Type, funcName: String, argTypes: List<Type>): Boolean
    fun lookUpType(instance: Type, funcName: String, argTypes: List<Type>): Type
    fun generateCallSignature(instance: Type, funcName: String, argTypes: List<Type>): String

    fun lookUpConstructor(className: SignatureString, argTypes: List<Type>): Type
    fun lookUpFieldType(instance: Type, fieldName: String): Type
    fun lookUpFieldType(modName: SignatureString, fieldName: String): Type
    fun classOf(type: Type.JvmType): Class<*>
}