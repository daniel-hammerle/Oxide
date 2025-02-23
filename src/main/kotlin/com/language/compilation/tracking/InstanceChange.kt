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

package com.language.compilation.tracking

import com.language.compilation.SignatureString
import com.language.compilation.Type
import java.util.UUID

sealed interface InstanceChange {
    data class New(val template: InstanceBuilder): InstanceChange
    data class Unionization(val newInstances: List<InstanceBuilder>): InstanceChange
    data class PropertyChanges(val changes: Map<String, InstanceChange>): InstanceChange
    data class UnionChanges(val changes: List<InstanceChange>): InstanceChange
    data class JvmChange(val generics: Map<String, BroadInstanceBuilder>) : InstanceChange
    data class ArrayChange(val itemChange: InstanceChange) : InstanceChange

    data object Nothing : InstanceChange
}

sealed interface InstanceAccessInstruction {
    data class Property(val name: String) : InstanceAccessInstruction
    data class UnionAccess(val type: Type) : InstanceAccessInstruction
    data object ItemType : InstanceAccessInstruction
}

sealed interface InstanceBuilder : BroadInstanceBuilder {
    data class Location(val argIdx: Int, val instructions: List<InstanceAccessInstruction>): InstanceBuilder
    data class New(val template: InstanceTemplate): InstanceBuilder
}


fun BroadForge.change(change: InstanceChange, argTypes: List<BroadForge>): BroadForge = when {
    this is InstanceForge -> this.changeBroad(change,argTypes )
    else -> BroadForge.Empty
}

fun InstanceForge.changeBroad(change: InstanceChange, args: List<BroadForge>): BroadForge {
    return when(change) {
        is InstanceChange.New -> change.template.buildBroad(args)
        InstanceChange.Nothing -> this
        is InstanceChange.PropertyChanges -> {
            this as StructInstanceForge
            change.changes.forEach { (name, change) ->
                members[name] = (members[name]!!.change(change, args) as? InstanceForge) ?: return BroadForge.Empty
            }
            this
        }

        is InstanceChange.UnionChanges -> {
            this as JoinedInstanceForge
            forges = forges.zip(change.changes).map { (it.first.change(it.second, args) as? InstanceForge) ?: return BroadForge.Empty }
            this
        }

        is InstanceChange.JvmChange -> {
            this as JvmInstanceForge
            generics.putAll(change.generics.mapValues { it.value.buildBroad(args) as InstanceForge })
            this
        }

        is InstanceChange.Unionization -> when(this) {
            is JoinedInstanceForge -> {
                this.forges += change.newInstances.map { (it.buildBroad(args) as? InstanceForge) ?: return BroadForge.Empty }
                this
            }

            else -> JoinedInstanceForge(listOf(this) + change.newInstances.map { (it.buildBroad(args) as? InstanceForge) ?: return BroadForge.Empty })
        }

        is InstanceChange.ArrayChange -> {
            this as ArrayInstanceForge
            itemForge = itemForge.change(change.itemChange, args) as? InstanceForge ?: return BroadForge.Empty
            this
        }
    }
}

fun InstanceForge.change(change: InstanceChange, args: List<InstanceForge>): InstanceForge = when(change) {
    is InstanceChange.New -> change.template.build(args)
    InstanceChange.Nothing -> this
    is InstanceChange.PropertyChanges -> {
        this as MemberChangeable
        change.changes.forEach { (name, change) ->
            this.definiteChange(name, member(name).change(change, args))
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
        generics.putAll(change.generics.mapValues { it.value.build(args) as InstanceForge })
        this
    }
    is InstanceChange.Unionization -> when(this) {
        is JoinedInstanceForge -> {
            this.forges += change.newInstances.map { it.build(args) }
            this
        }
        else -> JoinedInstanceForge(listOf(this) + change.newInstances.map { it.build(args) })
    }

    is InstanceChange.ArrayChange -> {
        this as ArrayInstanceForge
        itemForge = itemForge.change(change.itemChange, args)
        this
    }
}
fun InstanceBuilder.buildBroad(args: List<BroadForge>): BroadForge = when(this) {
    is InstanceBuilder.Location -> {
        (args[argIdx] as? InstanceForge)?.trace(instructions) ?: BroadForge.Empty
    }

    is InstanceBuilder.New -> {
        template.buildBroad(args)
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

        InstanceAccessInstruction.ItemType -> {
            this as ArrayInstanceForge
            itemForge
        }
    }
    return forge.trace(instructions.subList(fromIndex = 1, instructions.lastIndex))
}

fun InstanceTemplate.build(args: List<InstanceForge>): InstanceForge = when(this) {
    is InstanceTemplate.Const -> forge
    is InstanceTemplate.Joined -> JoinedInstanceForge(items.map { it.build(args) })
    is InstanceTemplate.Jvm -> JvmInstanceForge(generics.mapValuesToMutable { it.value.build(args) }, signatureString)
    is InstanceTemplate.Object -> StructInstanceForge(
        fields.mapValuesToMutable { it.value.build(args) },
        generics,
        signatureString
    )

    is InstanceTemplate.Array -> ArrayInstanceForge(itemForge.build(args))
}

fun InstanceTemplate.buildBroad(args: List<BroadForge>): BroadForge {
    return when(this) {
        is InstanceTemplate.Const -> forge
        is InstanceTemplate.Joined -> JoinedInstanceForge(items.map { (it.buildBroad(args) as? InstanceForge) ?: return BroadForge.Empty })
        is InstanceTemplate.Jvm -> JvmInstanceForge(generics.mapValuesToMutable { it.value.buildBroad(args) as InstanceForge }, signatureString)
        is InstanceTemplate.Object -> StructInstanceForge(
            fields.mapValuesToMutable { (it.value.buildBroad(args) as? InstanceForge) ?: return BroadForge.Empty },
            generics,
            signatureString
        )
        is InstanceTemplate.Array -> ArrayInstanceForge(itemForge.buildBroad(args) as? InstanceForge ?: return BroadForge.Empty)
    }
}

sealed interface InstanceTemplate {
    data class Const(val forge: InstanceForge) : InstanceTemplate
    data class Array(val itemForge: InstanceBuilder) : InstanceTemplate
    data class Jvm(val signatureString: SignatureString, val generics: Map<String, InstanceBuilder>) : InstanceTemplate
    data class Object(val signatureString: SignatureString, val fields: Map<String, InstanceBuilder>, val generics: Map<String, String>): InstanceTemplate
    data class Joined(val items: List<InstanceBuilder>): InstanceTemplate
}

sealed interface BroadInstanceBuilder {
    data object Unknown : BroadInstanceBuilder
    data class Unionized(val actual: InstanceBuilder) : BroadInstanceBuilder
}

fun BroadForge.toTemplate(instanceLookup: InstanceLookup): BroadInstanceBuilder = when(this) {
    is InstanceForge -> toTemplate(instanceLookup)
    is BroadForge.Unionized -> BroadInstanceBuilder.Unionized(forge.toTemplate(instanceLookup))
    is BroadForge.Empty -> BroadInstanceBuilder.Unknown
}

fun BroadInstanceBuilder.build(args: List<InstanceForge>) = when(this) {
    is BroadInstanceBuilder.Unknown -> BroadForge.Empty
    is BroadInstanceBuilder.Unionized -> BroadForge.Unionized(actual.build(args))
    is InstanceBuilder -> build(args)
}

fun BroadInstanceBuilder.buildBroad(args: List<BroadForge>): BroadForge {
    return when(this) {
        is BroadInstanceBuilder.Unknown -> BroadForge.Empty
        is BroadInstanceBuilder.Unionized -> BroadForge.Unionized((actual.buildBroad(args) as? InstanceForge) ?: return BroadForge.Empty)
        is InstanceBuilder -> buildBroad(args)
    }
}

interface InstanceLookup {
    fun find(id: UUID): InstanceBuilder.Location?

    companion object {
        fun make(argTypes: List<InstanceForge>): InstanceLookup {
            val entries = argTypes.map { mutableMapOf<UUID, List<InstanceAccessInstruction>>().apply { it.referenceAll(this, emptyList()) } }
            return InstanceLookupImpl(entries)
        }

        fun makeBroad(argTypes: List<BroadForge>): InstanceLookup  = make(argTypes.filterIsInstance<InstanceForge>())
    }
}



class InstanceLookupImpl(val instances: List<Map<UUID, List<InstanceAccessInstruction>>>) : InstanceLookup {
    override fun find(id: UUID): InstanceBuilder.Location? {
        val idx = instances.indexOfFirst { id in it }
        if (idx == -1) return null
        return InstanceBuilder.Location(idx, instances[idx][id]!!)
    }

}
