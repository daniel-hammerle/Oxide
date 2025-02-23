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