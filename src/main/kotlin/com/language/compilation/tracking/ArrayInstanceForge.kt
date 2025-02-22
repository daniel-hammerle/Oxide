package com.language.compilation.tracking

import com.language.compilation.Type
import java.util.*

data class ArrayInstanceForge(
    var itemForge: InstanceForge,
    override val id: UUID = UUID.randomUUID()
) : InstanceForge {
    override val type: Type
        get() = Type.Array(itemForge.type)


    override fun clone(processes: MutableMap<UUID, InstanceForge>): InstanceForge {
        return processes[id] ?: run { processes[id] = this; itemForge.clone(processes); this }
    }

    override fun referenceAll(
        references: MutableMap<UUID, List<InstanceAccessInstruction>>,
        prev: List<InstanceAccessInstruction>
    ) {
        references[id] = prev
        itemForge.referenceAll(references, prev + InstanceAccessInstruction.ItemType)
    }

    override fun compare(other: InstanceForge, instanceLookup: InstanceLookup): InstanceChange {
        if (this.id != other.id) return InstanceChange.New(this.toTemplate(instanceLookup))
        other as ArrayInstanceForge
        return InstanceChange.ArrayChange(itemForge.compare(other.itemForge, instanceLookup))
    }

    override fun toTemplate(instanceLookup: InstanceLookup): InstanceBuilder {
        return instanceLookup.find(id) ?: InstanceBuilder.New(InstanceTemplate.Array(itemForge.toTemplate(instanceLookup)))
    }

    override fun mergeMut(others: List<InstanceForge>): InstanceForge {
        val same = others.filter { it.id == id }.map { it as ArrayInstanceForge }
        itemForge = same.map { it.itemForge }.fold(itemForge) { acc, it -> acc.join(it)}

        val foreign = others.filter { it.id != id }
        if (foreign.isEmpty()) return this

        return foreign.fold(this as InstanceForge) { acc, it -> acc.join(it) }
    }
}