package com.language.compilation.variables

import com.language.compilation.TypedInstruction
import com.language.compilation.tracking.InstanceForge
import java.util.UUID

interface VariableProvider {
    fun get(parent: VariableMapping): TypedInstruction
    fun put(value: TypedInstruction, parent: VariableMapping): TypedInstruction
    fun delete(parent: VariableMapping) {}
    val physicalId: Int? get() = null
    fun type(parent: VariableMapping) = forge.type
    val forge: InstanceForge
    fun clone(forges: MutableMap<UUID, InstanceForge>): VariableProvider
}