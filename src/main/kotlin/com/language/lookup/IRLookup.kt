package com.language.lookup

import com.language.TemplatedType
import com.language.compilation.FunctionCandidate
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifier
import com.language.compilation.modifiers.Modifiers

interface IRLookup {

    /**
     * It looks up what arguments of a function are generic and returns a map
     * containing their names and their corresponding index in the function arguments
     */
    suspend fun lookUpGenericTypes(instance: Type, funcName: String, argTypes: List<Type>): Map<String, Int>

    /**
     * Checks whether a given method has a generic return type
     */
    suspend fun hasGenericReturnType(instance: Type, funcName: String, argTypes: List<Type>): Boolean

    /**
     * Returns the function candidate for a given static function
     * # Note
     * If the given module or class doesn't have a matching static method it will throw
     */
    suspend fun lookUpCandidate(modName: SignatureString, funcName: String, argTypes: List<Type>): FunctionCandidate

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
    suspend fun lookUpCandidate(instance: Type, funcName: String, argTypes: List<Type>): FunctionCandidate

    /**
     * Checks if the given type has the interface expected.
     * Will throw if the interface doesn't exist
     */
    suspend fun typeHasInterface(type: Type, interfaceType: SignatureString): Boolean

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
    suspend fun lookUpConstructor(className: SignatureString, argTypes: List<Type>): FunctionCandidate

    /**
     * Returns the type of the field or throw in case it doesn't exist
     * # Note
     * When called with primitives it will throw
     */
    suspend fun lookUpFieldType(instance: Type, fieldName: String): Type

    /**
     * Returns the type of the static field or throw in case it doesn't exist
     */
    suspend fun lookUpFieldType(modName: SignatureString, fieldName: String): Type

    /**
     * Returns the fields and their corresponding types in the order
     * they are defined in.
     * # Note:
     * This only works for **native** structs
     */
    fun lookUpOrderedFields(className: SignatureString): List<Pair<String, TemplatedType>>

    suspend fun hasModifier(instance: Type, modifier: Modifier): Boolean

    suspend fun satisfiesModifiers(instance: Type, modifiers: Modifiers): Boolean

    suspend fun TemplatedType.populate(generics: Map<String, Type>): Type
}