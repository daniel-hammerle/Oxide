// Copyright 2025 Daniel Hammerle
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.language.eval

import com.language.compilation.*
import com.language.compilation.tracking.StructInstanceForge
import com.language.lookup.IRLookup

val PrimitiveTypeObject = SignatureString("std::types::Primitive")
val StructTypeObject = SignatureString("std::types::StructType")
val ArrayTypeObject = SignatureString("std::types::ArrayType")
val FieldObject = SignatureString("std::types::Field")


suspend fun createTypeObject(type: Type, lookup: IRLookup, history: History): TypedInstruction.Const {
    return when(type) {
        is Type.BoolT -> createConstObject(PrimitiveTypeObject, listOf(TypedInstruction.LoadConstString("bool")), lookup, history)
        Type.DoubleT -> createConstObject(PrimitiveTypeObject, listOf(TypedInstruction.LoadConstString("f64")), lookup, history)
        Type.IntT -> createConstObject(PrimitiveTypeObject, listOf(TypedInstruction.LoadConstString("i32")), lookup, history)
        Type.String -> createConstObject(PrimitiveTypeObject, listOf(TypedInstruction.LoadConstString("str")), lookup, history)
        is Type.Array -> createConstObject(ArrayTypeObject, listOf(createTypeObject(type.itemType, lookup, history)), lookup, history)
        Type.BoolArray -> createConstObject(
            ArrayTypeObject,
            listOf(createConstObject(PrimitiveTypeObject, listOf(TypedInstruction.LoadConstString("bool")), lookup, history)),
            lookup, history
        )
        Type.DoubleArray -> createConstObject(
            ArrayTypeObject,
            listOf(createConstObject(PrimitiveTypeObject, listOf(TypedInstruction.LoadConstString("f64")), lookup, history)),
            lookup, history
        )
        Type.IntArray -> createConstObject(
            ArrayTypeObject,
            listOf(createConstObject(PrimitiveTypeObject, listOf(TypedInstruction.LoadConstString("i32")), lookup, history)),
            lookup, history
        )
        is Type.BasicJvmType -> {
            val fields = lookup.lookUpOrderedFields(type.signature)
            val fieldObjects = fields.map { (name, tp) ->
                createConstObject(
                    FieldObject,
                    listOf(
                        TypedInstruction.LoadConstString(name),
                        createTypeObject(with(lookup) { tp.populate(type.genericTypes)}, lookup, history)),
                    lookup, history
                )
            }
            val fieldsArray = TypedInstruction.LoadConstConstArray(fieldObjects, ArrayType.Object, Type.BasicJvmType(FieldObject, emptyMap()))
            val modifierArray = TypedInstruction.LoadConstConstArray(emptyList(), ArrayType.Object, Type.UninitializedGeneric)
            createConstObject(
                StructTypeObject,
                listOf(TypedInstruction.LoadConstString(type.signature.oxideNotation), fieldsArray, modifierArray),
                lookup, history
            )

        }
        is Type.Lambda -> TODO()
        Type.Never -> TODO()
        Type.Nothing -> TODO()
        Type.Null -> TODO()
        Type.UninitializedGeneric -> TODO()
        is Type.Union -> TODO()
        Type.ByteT -> TODO()
        Type.CharT -> TODO()
        Type.FloatT -> TODO()
        Type.LongT -> TODO()
    }
}

suspend fun createConstObject(signature: SignatureString, constructorArgs: List<TypedInstruction.Const>, lookup: IRLookup, history: History): TypedInstruction.ConstObject {
    val fields = lookup.lookUpOrderedFields(signature)
    val result = lookup.lookUpConstructor(signature, constructorArgs.map { it.forge }, history)
    return TypedInstruction.ConstObject(result.returnForge as StructInstanceForge, result, fields.asSequence().map { it.first }.zip(constructorArgs.asSequence()).toList())
}