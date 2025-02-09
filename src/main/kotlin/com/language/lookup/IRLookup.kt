package com.language.lookup

import com.language.TemplatedType
import com.language.compilation.*
import com.language.compilation.modifiers.Modifier
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.tracking.BroadForge
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.variables.VariableManager

interface IRLookup {

    /**
     * Checks whether a given method has a generic return type
     */
    suspend fun hasGenericReturnType(instance: Type, funcName: String, argTypes: List<Type>): Boolean

    /**
     * Returns the function candidate for a given static function
     * # Note
     * If the given module or class doesn't have a matching static method it will throw
     */
    suspend fun lookUpCandidate(modName: SignatureString, funcName: String, argTypes: List<InstanceForge>, history: History, generics: Map<String, Type.Broad> = emptyMap()): FunctionCandidate

    /**
     * Returns the function candidate for a given method
     * A candidate can be a:
     * - Literal method call
     * - A static call to an impl block class
     * - an Interface call
     *
     * # Note
     * If the type doesn't have a matching method, the function will throw
     */
    suspend fun lookUpCandidate(instance: InstanceForge, funcName: String, argTypes: List<InstanceForge>, history: History): FunctionCandidate


    /**
     * Returns the function candidate for a given method
     * A candidate can be a:
     * - Literal method call
     * - A static call to an impl block class
     * - an Interface call
     *
     * # Note
     * If the type doesn't have a matching method, the function will throw
     */
    suspend fun lookUpCandidateUnknown(instance: InstanceForge, funcName: String, argTypes: List<BroadForge>, history: History): BroadForge

    suspend fun lookUpCandidateUnknown(modName: SignatureString, funcName: String, argTypes: List<BroadForge>, history: History, generics: Map<String, Type.Broad> = emptyMap()): BroadForge


    /**
     * Checks if the given type has the interface expected.
     * Will throw if the interface doesn't exist
     */
    suspend fun typeHasInterface(type: Type, interfaceType: SignatureString): Boolean

    /**
     *
     */
    suspend fun processInlining(
        variables: VariableManager,
        instance: TypedInstruction,
        funcName: String,
        args: List<TypedInstruction>,
        untypedArgs: List<Instruction>,
        generics: Map<String, Type>,
        history: History
    ): TypedInstruction?


    suspend fun processInlining(
        variables: VariableManager,
        modName: SignatureString,
        funcName: String,
        args: List<TypedInstruction>,
        untypedArgs: List<Instruction>,
        generics: Map<String, Type>,
        history: History
    ): TypedInstruction?
    /**
     * Returns a new instance of Self with the new ModFrame
     * # Note
     * It will ignore modNames that don't have matching entries in the [nativeModules]
     */
    fun newModFrame(modNames: Set<SignatureString>): IRLookup

    /**
     * Returns a constructor which matches the argument types
     * The function will throw if the class / struct doesn't exist or no matching constructor is found
     */
    suspend fun lookUpConstructor(className: SignatureString, argTypes: List<InstanceForge>): FunctionCandidate
    suspend fun lookUpConstructorUnknown(className: SignatureString, argTypes: List<BroadForge>): BroadForge

    /**
     * Returns the type of the field or throw in case it doesn't exist
     * # Note
     * When called with primitives it will throw
     */
    suspend fun lookUpFieldType(instance: Type, fieldName: String): Type?
    suspend fun lookUpPhysicalFieldType(instance: Type, fieldName: String): Type

    /**
     * Returns the type of the static field or throw in case it doesn't exist
     */
    suspend fun lookUpFieldType(modName: SignatureString, fieldName: String): Type
    suspend fun lookUpFieldForge(modName: SignatureString, fieldName: String): InstanceForge

    /**
     * Returns the fields and their corresponding types in the order
     * they are defined in.
     * # Note:
     * This only works for **native** structs
     */
    fun lookUpOrderedFields(className: SignatureString): List<Pair<String, TemplatedType>>

    suspend fun lookupOrderGenerics(className: SignatureString): List<String>

    suspend fun hasModifier(instance: Type, modifier: Modifier): Boolean

    suspend fun satisfiesModifiers(instance: Type, modifiers: Modifiers): Boolean

    suspend fun lookupLambdaInit(signatureString: SignatureString): FunctionCandidate

    suspend fun lookupLambdaInvoke(signatureString: SignatureString, argTypes: List<InstanceForge>, history: History): FunctionCandidate

    suspend fun TemplatedType.populate(generics: Map<String, Type>, box: Boolean = false): Type
}