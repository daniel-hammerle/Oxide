package com.language.compilation

interface IRModuleLookup {
    val nativeModules: Set<IRModule>
    suspend fun lookUpCandidate(modName: SignatureString, funcName: String, argTypes: List<Type>): FunctionCandidate
    fun lookUpGenericTypes(instance: Type, funcName: String, argTypes: List<Type>): Map<String, Int>
    fun hasGenericReturnType(instance: Type, funcName: String, argTypes: List<Type>): Boolean
    fun lookUpCandidate(instance: Type, funcName: String, argTypes: List<Type>): FunctionCandidate
    fun generateCallSignature(instance: Type, funcName: String, argTypes: List<Type>): String

    fun lookUpConstructor(className: SignatureString, argTypes: List<Type>): FunctionCandidate
    fun lookUpFieldType(instance: Type, fieldName: String): Type
    fun lookUpFieldType(modName: SignatureString, fieldName: String): Type
    fun classOf(type: Type.JvmType): Class<*>

    fun lookUpOrderedFields(className: SignatureString): List<Pair<String, Type>>
}