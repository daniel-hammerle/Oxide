package com.language.parser

class Variables (
    private val parent: Variables?,
    private val entries: MutableSet<String>
) {
    operator fun contains(value: String): Boolean = entries.contains(value) || (parent?.contains(value) ?: false)

    fun put(name: String) = entries.add(name)

    fun child() = Variables(this, mutableSetOf())

    companion object {
        fun withEntries(entries: Set<String>) = Variables(null, entries.toMutableSet())
    }
}