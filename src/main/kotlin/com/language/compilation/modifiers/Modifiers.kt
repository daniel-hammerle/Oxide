package com.language.compilation.modifiers


@JvmInline
value class Modifiers(private val modifiers: Int) {
    fun isError() = modifiers.isSet(Modifier.Error.position)

    fun isPublic() = modifiers.isSet(Modifier.Public.position)

    fun isModifier(modifier: Modifier) = modifiers.isSet(modifier.position)

    companion object {
        val Empty = Modifiers(0)
        val FunctionModifiers = modifiers { setPublic(); setTyped(); setModifier(Modifier.Inline) }
        val StructModifiers = modifiers { setPublic(); setError(); setStatic() }
        val ImplBlockModifiers = modifiers { setPublic(); setTyped() }
    }

    fun isSubsetOf(other: Modifiers) = (modifiers and other.modifiers) == modifiers

    fun hasAllModifiersOf(other: Modifiers) = other.isSubsetOf(this)

    override fun toString(): String {
        val modifiers = Modifier.entries.mapNotNull { if (isModifier(it)) it.name else null  }.joinToString(", ")
        return "[$modifiers]"
    }
}

