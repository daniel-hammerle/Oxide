package com.language.compilation.modifiers

class ModifierBuilder {
    private var modifiers: Int = 0

    fun setError() {
        modifiers = modifiers.setBit(Modifier.Error.position)
    }
    fun setPublic() {
        modifiers = modifiers.setBit(Modifier.Public.position)
    }
    fun setStatic() {
        modifiers = modifiers.setBit(Modifier.Static.position)
    }
    fun setTyped() {
        modifiers = modifiers.setBit(Modifier.Typed.position)
    }
    fun setModifier(modifier: Modifier) {
        modifiers =  modifiers.setBit(modifier.position)
    }

    fun build() = Modifiers(modifiers)
}

inline fun modifiers(closure: ModifierBuilder.() -> Unit): Modifiers {
    return ModifierBuilder().apply(closure).build()
}