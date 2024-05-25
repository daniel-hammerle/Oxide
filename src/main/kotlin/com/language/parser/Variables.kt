package com.language.parser

interface Variables {
    operator fun contains(value: String): Boolean

    fun put(name: String): Boolean

    fun child(): Variables

    fun monitoredChild(): MonitoredVariables
}

class BasicVariables (
    private val parent: Variables?,
    private val entries: MutableSet<String>
) : Variables {
    override operator fun contains(value: String): Boolean = entries.contains(value) || (parent?.contains(value) ?: false)

    override fun put(name: String) = entries.add(name)

    override fun child() = BasicVariables(this, mutableSetOf())

    override fun monitoredChild() = MonitoredVariables(this, mutableSetOf())

    companion object {
        fun withEntries(entries: Set<String>) = BasicVariables(null, entries.toMutableSet())
    }
}

class MonitoredVariables(
    private val parent: Variables,
    private val entries: MutableSet<String>
): Variables {

    private val usedParentVars: MutableSet<String> = mutableSetOf()

    override fun contains(value: String): Boolean {
        if (entries.contains(value)) return true
        if (parent.contains(value)) {
            usedParentVars.add(value)
            return true
        }
        return false
    }

    override fun put(name: String): Boolean  = entries.add(name)

    override fun child(): Variables  = BasicVariables(this, mutableSetOf())

    fun usedParentVars(): Set<String> = usedParentVars

    override fun monitoredChild(): MonitoredVariables = MonitoredVariables(this, mutableSetOf())

}