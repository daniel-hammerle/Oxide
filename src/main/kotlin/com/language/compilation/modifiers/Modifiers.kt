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

package com.language.compilation.modifiers


@JvmInline
value class Modifiers(private val modifiers: Int) {
    fun isError() = modifiers.isSet(Modifier.Error.position)

    fun isPublic() = modifiers.isSet(Modifier.Public.position)

    fun isModifier(modifier: Modifier) = modifiers.isSet(modifier.position)

    companion object {
        val Empty = Modifiers(0)
        val FunctionModifiers = modifiers { setPublic(); setTyped(); setModifier(Modifier.Inline); setModifier(Modifier.Intrinsic) }
        val StructModifiers = modifiers { setPublic(); setError(); setStatic(); setModifier(Modifier.Extern) }
        val ImplBlockModifiers = modifiers { setPublic(); setTyped() }
    }

    fun isSubsetOf(other: Modifiers) = (modifiers and other.modifiers) == modifiers

    fun hasAllModifiersOf(other: Modifiers) = other.isSubsetOf(this)

    override fun toString(): String {
        val modifiers = Modifier.entries.mapNotNull { if (isModifier(it)) it.name else null  }.joinToString(", ")
        return "[$modifiers]"
    }
}

