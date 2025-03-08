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

package com.language.compilation.templatedType

import com.language.TemplatedType
import com.language.compilation.GenericType
import com.language.lookup.IRLookup
import com.language.compilation.Type
import com.language.compilation.join
import com.language.compilation.modifiers.Modifiers
import com.language.lookup.oxide.lazyTransform

suspend fun Iterable<TemplatedType>.matchesImpl(
    other: List<Type.Broad>,
    generics: MutableMap<String, Type> = mutableMapOf(),
    modifiers: Map<String, GenericType>,
    lookup: IRLookup
) : Boolean {
    return this.zip(other).all { (first, second) -> first.matchesImpl(second, generics, modifiers, lookup) }
}

suspend fun Iterable<TemplatedType>.matchesImpl(
    other: Iterable<Type>,
    generics: MutableMap<String, Type> = mutableMapOf(),
    modifiers: Map<String, GenericType>,
    lookup: IRLookup
) : Boolean {
    return this.zip(other).all { (first, second) -> first.matchesImpl(second, generics, modifiers, lookup) }
}

suspend fun TemplatedType.matchesSubset(
    type: Type.Broad,
    generics: MutableMap<String, Type> = mutableMapOf(),
    modifiers: Map<String, GenericType>,
    lookup: IRLookup
): Boolean = when(type) {
    is Type.Broad.Known -> matchesSubset(type.type, generics, modifiers, lookup)
    is Type.Broad.UnknownUnionized -> true
    Type.Broad.Unset -> true
}

suspend fun TemplatedType.matchesSubset(
    type: Type,
    generics: MutableMap<String, Type> = mutableMapOf(),
    modifiers: Map<String, GenericType>,
    lookup: IRLookup
) : Boolean {
    return when(this) {
        is TemplatedType.Union -> {
            if (type is Type.Union) {
               type.entries.all { this.matchesSubset(it, generics, modifiers, lookup) }
            } else {
                types.any { it.matchesSubset(type, generics, modifiers, lookup) }
            }
        }
        is TemplatedType.Complex -> {
            if (type !is Type.JvmType || !(type.signature == signatureString || lookup.typeHasInterface(type, signatureString))) {
                return false
            }
            type.genericTypes.entries.zip(this.generics).forEach { (entry, template) ->
                when(val v = entry.value) {
                    is Type.UninitializedGeneric -> {
                        println("Unset type ($type cannot match $this)")
                        println("Defaulting to true")
                    }
                    else -> when (template.matchesSubset(v, generics, modifiers, lookup)) {
                        false-> return false
                        else -> {}
                    }

                }
            }
            true
        }
        is TemplatedType.Generic -> when {
            modifiers[name]?.modifiers is Modifiers && modifiers[name]?.modifiers != Modifiers.Empty && !lookup.satisfiesModifiers(type, modifiers[name]?.modifiers!!) -> {
                false
            }
            else -> {
                generics.apply { put(name, get(name)?.join(type) ?: type) }
                true
            }
        }
        is TemplatedType.Array -> {
            type is Type.JvmArray && itemType.matchesSubset(type.itemType, generics, modifiers, lookup)
        }
        TemplatedType.BoolT ->type is Type.BoolT
        TemplatedType.DoubleT ->type == Type.DoubleT
        TemplatedType.IntT -> type == Type.IntT
        TemplatedType.Null -> type == Type.Null
        TemplatedType.Nothing -> type == Type.Nothing
        TemplatedType.Never -> type == Type.Never
        TemplatedType.ByteT -> type == Type.ByteT
        TemplatedType.CharT -> type == Type.CharT
        TemplatedType.FloatT -> type == Type.FloatT
        TemplatedType.LongT -> type == Type.LongT
    }
}


suspend fun TemplatedType.matchesImpl(
    type: Type,
    generics: MutableMap<String, Type> = mutableMapOf(),
    modifiers: Map<String, GenericType>,
    lookup: IRLookup
): Boolean {
    return when(this) {
        is TemplatedType.Union -> {
            if (type !is Type.Union) {
                return false
            }
            val entries = type.entries.toMutableSet()
            for (templateEntry in types) {
                val entry = entries.firstOrNull { templateEntry.matchesImpl(it, generics, modifiers, lookup) } ?: return false
                entries.remove(entry)
            }
            if (entries.isNotEmpty()) {
                val wildcard = types.find { it.isWildCard(modifiers.lazyTransform { _, t -> t.modifiers }) } as? TemplatedType.Generic
                if (wildcard == null) {
                    return false
                }
                generics[wildcard.name] = generics[wildcard.name]!!.join(Type.Union(entries))
            }
            true
        }
        is TemplatedType.Complex -> {
            if (type !is Type.JvmType || !(type.signature == signatureString || lookup.typeHasInterface(type, signatureString))) {
                return false
            }
            (this.generics).zip(type.genericTypes.entries).forEach { (template, entry) ->
                when(val v = entry.value) {
                    is Type.UninitializedGeneric -> {
                        println("Unset type ($type cannot match $this)")
                        println("Defaulting to true")
                    }
                    else -> when (template.matchesImpl(v, generics, modifiers, lookup)) {
                        false-> return false
                        else -> {}
                    }

                }
            }
            true
        }
        is TemplatedType.Generic -> {
            when {
                modifiers[name]?.modifiers is Modifiers && modifiers[name]?.modifiers != Modifiers.Empty && !lookup.satisfiesModifiers(type, modifiers[name]?.modifiers!!) -> {
                    return false
                }
            }

            generics.apply { put(name, get(name)?.join(type) ?: type) }
            true
        }
        is TemplatedType.Array -> {
            type is Type.JvmArray && itemType.matchesImpl(type.itemType, generics, modifiers, lookup)
        }
        TemplatedType.BoolT ->type is Type.BoolT
        TemplatedType.DoubleT ->type == Type.DoubleT
        TemplatedType.IntT -> type == Type.IntT
        TemplatedType.Null -> type == Type.Null
        TemplatedType.Nothing -> type == Type.Nothing
        TemplatedType.Never -> type == Type.Never
        TemplatedType.ByteT -> type == Type.ByteT
        TemplatedType.CharT -> type == Type.CharT
        TemplatedType.FloatT -> type == Type.FloatT
        TemplatedType.LongT -> type == Type.LongT
    }
}


fun TemplatedType.scope(
    generics: Map<String, TemplatedType>
): TemplatedType {
    return when(this) {
        is TemplatedType.Array -> TemplatedType.Array(itemType.scope(generics))
        is TemplatedType.Complex -> TemplatedType.Complex(signatureString, this.generics.map { it.scope(generics) })
        is TemplatedType.Union -> TemplatedType.Union(types.map { it.scope(generics) }.toSet())
        is TemplatedType.Generic -> generics[name] ?: error("No substitute found for generic $name")
       else -> this
    }
}

suspend fun TemplatedType.matchesImpl(
    type: Type.Broad,
    generics: MutableMap<String, Type> = mutableMapOf(),
    modifiers: Map<String, GenericType>,
    lookup: IRLookup
): Boolean {
    return when(type) {
        is Type.Broad.Known -> this@matchesImpl.matchesImpl(type.type, generics, modifiers, lookup)
        Type.Broad.Unset -> true
        is Type.Broad.UnknownUnionized -> true //assuming unknown always matches
    }
}

fun TemplatedType.isWildCard(modifiers: Map<String, Modifiers>) = this is TemplatedType.Generic && (name !in modifiers || modifiers[name] == Modifiers.Empty)

