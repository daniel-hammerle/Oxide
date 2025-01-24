package com.language.compilation.tracking

import com.language.compilation.Type
import java.util.UUID

class BasicInstanceForge(override val type: Type, override val id: UUID = UUID.randomUUID()) : InstanceForge {

    override fun clone(processes: MutableMap<UUID, InstanceForge>): InstanceForge {
        return processes[id] ?: BasicInstanceForge(type, id).also { processes[id] = it }
    }

    override fun referenceAll(
        references: MutableMap<UUID, List<InstanceAccessInstruction>>,
        prev: List<InstanceAccessInstruction>
    ) {
        references[id] = prev
    }

    override fun compare(
        other: InstanceForge,
        instanceLookup: InstanceLookup
    ): InstanceChange {
        if (other.id == id) {
            return InstanceChange.Nothing
        }

        return InstanceChange.New(other.toTemplate(instanceLookup))
    }

    override fun toTemplate(instanceLookup: InstanceLookup): InstanceBuilder {
        return InstanceBuilder.New(InstanceTemplate.Const(this))
    }

    override fun mergeMut(others: List<InstanceForge>): InstanceForge {
        return others.filter { it.id != id }.fold(this as InstanceForge) { acc, it -> acc.join(it)  }
    }

}