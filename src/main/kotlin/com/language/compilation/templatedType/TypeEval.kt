package com.language.compilation.templatedType

import com.language.TemplatedType
import com.language.lookup.IRLookup
import com.language.compilation.Type
import com.language.compilation.join
import com.language.compilation.modifiers.Modifiers

suspend fun TemplatedType.matches(
    type: Type,
    generics: MutableMap<String, Type> = mutableMapOf(),
    modifiers: Map<String, Modifiers>,
    lookup: IRLookup
): Boolean {
    return when(this) {
        is TemplatedType.Union -> {
            if (type !is Type.Union) {
                return false
            }
            val entries = type.entries.toMutableSet()
            for (templateEntry in types) {
                val entry = entries.firstOrNull { templateEntry.matches(it, generics, modifiers, lookup) } ?: return false
                entries.remove(entry)
            }
            if (entries.isNotEmpty()) {
                val wildcard = types.find { it.isWildCard(modifiers) } as? TemplatedType.Generic
                if (wildcard == null) {
                    return false
                }
                generics[wildcard.name] = generics[wildcard.name]!!.join(Type.Union(entries))
            }
            true
        }
        is TemplatedType.Complex -> {
            if (type !is Type.JvmType || type.signature != signatureString) {
                return false
            }
            type.genericTypes.entries.zip(this.generics).forEach { (entry, template) ->
                when(val v = entry.value) {
                    is Type.BroadType.Unset -> error("UNknwown type")
                    is Type.BroadType.Known -> when (template.matches(v.type, generics, modifiers, lookup)) {
                        false-> return false
                        else -> {}
                    }
                }
            }
            true
        }
        is TemplatedType.Generic -> {
            when {
                modifiers[name] is Modifiers && modifiers[name] != Modifiers.Empty && !lookup.satisfiesModifiers(type, modifiers[name]!!) -> {
                    return false
                }
            }

            generics.apply { put(name, get(name)?.join(type) ?: type) }
            true
        }
        is TemplatedType.Array -> {
            type is Type.Array && itemType.matches(type.itemType, generics, modifiers, lookup)
        }
        TemplatedType.BoolT ->type is Type.BoolT
        TemplatedType.DoubleT ->type == Type.DoubleT
        TemplatedType.IntT -> type == Type.IntT
        TemplatedType.Null -> type == Type.Null
    }
}

suspend fun TemplatedType.matches(
    type: Type.BroadType,
    generics: MutableMap<String, Type> = mutableMapOf(),
    modifiers: Map<String, Modifiers>,
    lookup: IRLookup
): Boolean {
    return when(type) {
        is Type.BroadType.Known -> matches(type.type, generics, modifiers, lookup)
        Type.BroadType.Unset -> error("")
    }
}

fun TemplatedType.isWildCard(modifiers: Map<String, Modifiers>) = this is TemplatedType.Generic && (name !in modifiers || modifiers[name] == Modifiers.Empty)

