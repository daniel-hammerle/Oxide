package com.language.compilation.tracking

import com.language.compilation.Type
import java.util.*

data class ArrayInstanceForge(
    val itemForge: InstanceForge,
    override val id: UUID = UUID.randomUUID()
) : InstanceForge {
    override val type: Type
        get() = Type.Array(itemForge.toBroadType())


    override fun clone(processes: MutableMap<UUID, InstanceForge>): InstanceForge {
        TODO("Not yet implemented")
    }

    override fun referenceAll(
        references: MutableMap<UUID, List<InstanceAccessInstruction>>,
        prev: List<InstanceAccessInstruction>
    ) {
        TODO("Not yet implemented")
    }

    override fun compare(other: InstanceForge, instanceLookup: InstanceLookup): InstanceChange {
        TODO("Not yet implemented")
    }

    override fun toTemplate(instanceLookup: InstanceLookup): InstanceBuilder {
        TODO("Not yet implemented")
    }

    override fun mergeMut(others: List<InstanceForge>): InstanceForge {
        TODO("Not yet implemented")
    }
}