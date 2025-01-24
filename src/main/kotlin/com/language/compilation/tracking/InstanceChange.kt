package com.language.compilation.tracking

import com.language.compilation.SignatureString
import com.language.compilation.Type
import java.util.UUID

sealed interface InstanceChange {
    data class New(val template: InstanceBuilder): InstanceChange
    data class Unionization(val newInstances: List<InstanceBuilder>): InstanceChange
    data class PropertyChanges(val changes: Map<String, InstanceChange>): InstanceChange
    data class UnionChanges(val changes: List<InstanceChange>): InstanceChange
    data class JvmChange(val generics: Map<String, Type.Broad>) : InstanceChange
    data object Nothing : InstanceChange
}

sealed interface InstanceAccessInstruction {
    data class Property(val name: String) : InstanceAccessInstruction
    data class UnionAccess(val type: Type) : InstanceAccessInstruction
}

sealed interface InstanceBuilder {
    data class Location(val argIdx: Int, val instructions: List<InstanceAccessInstruction>): InstanceBuilder
    data class New(val template: InstanceTemplate): InstanceBuilder
}

fun InstanceForge.change(change: InstanceChange, args: List<InstanceForge>): InstanceForge = when(change) {
    is InstanceChange.New -> change.template.build(args)
    InstanceChange.Nothing -> this
    is InstanceChange.PropertyChanges -> {
        this as StructInstanceForge
        change.changes.forEach { (name, change) ->
            members[name] = members[name]!!.change(change, args)
        }
        this
    }
    is InstanceChange.UnionChanges -> {
        this as JoinedInstanceForge
        forges = forges.zip(change.changes).map { it.first.change(it.second, args) }
        this
    }
    is InstanceChange.JvmChange -> {
        this as JvmInstanceForge
        generics.putAll(change.generics)
        this
    }
    is InstanceChange.Unionization -> when(this) {
        is JoinedInstanceForge -> {
            this.forges = forges + change.newInstances.map { it.build(args) }
            this
        }
        else -> JoinedInstanceForge(listOf(this) + change.newInstances.map { it.build(args) })
    }
}

fun InstanceBuilder.build(args: List<InstanceForge>): InstanceForge = when(this) {
    is InstanceBuilder.Location -> {
        args[argIdx].trace(instructions)
    }
    is InstanceBuilder.New -> {
        template.build(args)
    }
}

fun InstanceForge.trace(instructions: List<InstanceAccessInstruction>): InstanceForge {
    if (instructions.isEmpty()) return this
    val forge = when (val ins = instructions.first()) {
        is InstanceAccessInstruction.Property -> {
            this as StructInstanceForge
            members[ins.name]!!
        }
        is InstanceAccessInstruction.UnionAccess -> {
            this as JoinedInstanceForge
            forges.first { it.type == ins.type }
        }
    }
    return forge.trace(instructions.subList(fromIndex = 1, instructions.lastIndex))
}

fun InstanceTemplate.build(args: List<InstanceForge>): InstanceForge = when(this) {
    is InstanceTemplate.Const -> forge
    is InstanceTemplate.Joined -> JoinedInstanceForge(items.map { it.build(args) })
    is InstanceTemplate.Jvm -> JvmInstanceForge(generics.toMutableMap(), signatureString)
    is InstanceTemplate.Object -> StructInstanceForge(
        fields.mapValuesToMutable { it.value.build(args) },
        generics,
        signatureString
    )
}

sealed interface InstanceTemplate {
    data class Const(val forge: InstanceForge) : InstanceTemplate
    data class Jvm(val signatureString: SignatureString, val generics: Map<String, Type.Broad>) : InstanceTemplate
    data class Object(val signatureString: SignatureString, val fields: Map<String, InstanceBuilder>, val generics: Map<String, String>): InstanceTemplate
    data class Joined(val items: List<InstanceBuilder>): InstanceTemplate
}
interface InstanceLookup {
    fun find(id: UUID): InstanceBuilder.Location?

    companion object {
        fun make(argTypes: List<InstanceForge>): InstanceLookup {
            val entries = argTypes.map { mutableMapOf<UUID, List<InstanceAccessInstruction>>().apply { it.referenceAll(this, emptyList()) } }
            return InstanceLookupImpl(entries)
        }
    }
}



class InstanceLookupImpl(val instances: List<Map<UUID, List<InstanceAccessInstruction>>>) : InstanceLookup {
    override fun find(id: UUID): InstanceBuilder.Location? {
        val idx = instances.indexOfFirst { id in it }
        if (idx == -1) return null
        return InstanceBuilder.Location(idx, instances[idx][id]!!)
    }

}
