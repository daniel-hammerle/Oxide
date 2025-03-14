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

import com.language.compilation.History
import com.language.compilation.Type
import com.language.compilation.join
import java.util.UUID

fun InstanceForge.join(other: InstanceForge): InstanceForge {
    return when {
        this.id == other.id -> this
        this is JoinedInstanceForge && other is JoinedInstanceForge -> JoinedInstanceForge(forges + other.forges)
        this is JoinedInstanceForge -> JoinedInstanceForge(forges + other)
        this is BasicInstanceForge && other is BasicInstanceForge && type == other.type -> this
        this is BasicInstanceForge && this.type == Type.Never -> other
        this is BasicInstanceForge && this.type == Type.UninitializedGeneric -> other
        other is BasicInstanceForge && other.type == Type.Never -> this
        other is BasicInstanceForge && other.type == Type.UninitializedGeneric -> this
        else -> JoinedInstanceForge(listOf(this, other))
    }
}

fun InstanceForge.member(name: String): InstanceForge = when(this) {
    is StructInstanceForge -> memberForge(name)!!
    is JoinedInstanceForge -> forges.map { it.member(name) }.reduce { acc, it -> acc.join(it) }
    else -> error("unrachable $this")
}

interface GenericDestructableForge {
    fun destructGeneric(name: String): InstanceForge
}

sealed interface BroadForge {
    data object Empty : BroadForge {
        override fun clone(processes: MutableMap<UUID, InstanceForge>): BroadForge {
            return Empty
        }
    }

    data class Unionized(val forge: InstanceForge): BroadForge {
        override fun clone(processes: MutableMap<UUID, InstanceForge>): BroadForge {
            return Unionized(forge.clone(processes))
        }
    }

    fun clone(processes: MutableMap<UUID, InstanceForge>): BroadForge

}

fun BroadForge.compare(other: BroadForge, instanceLookup: InstanceLookup): InstanceChange? = when {
    this is InstanceForge && other is InstanceForge -> compare(other, instanceLookup)
    else -> null
}


fun BroadForge.toBroadType(): Type.Broad = when(this) {

    BroadForge.Empty -> Type.Broad.Unset
    is BroadForge.Unionized -> Type.Broad.UnknownUnionized(forge.type)
    is InstanceForge -> Type.Broad.Known(type)
}



fun BroadForge.join(other: BroadForge): BroadForge = when {
    this == BroadForge.Empty && other == BroadForge.Empty -> BroadForge.Empty

    this == BroadForge.Empty && other is BroadForge.Unionized ->other
    this == BroadForge.Empty && other is InstanceForge ->BroadForge.Unionized(other)
    other == BroadForge.Empty && this is BroadForge.Unionized ->this
    other == BroadForge.Empty && this is InstanceForge ->BroadForge.Unionized(this)

    this is BroadForge.Unionized && other is BroadForge.Unionized -> BroadForge.Unionized(forge.join(other.forge))
    this is BroadForge.Unionized && other is InstanceForge -> BroadForge.Unionized(forge.join(other))
    this is InstanceForge && other is BroadForge.Unionized -> BroadForge.Unionized(this.join(other.forge))

    this is InstanceForge && other is InstanceForge -> this.join(other)
    else -> error("Unreachable i hope")
}


interface InstanceForge : BroadForge {
    val type: Type
    val id: UUID

    companion object {
        val ConstString = BasicInstanceForge(Type.String)
        val ConstInt = BasicInstanceForge(Type.IntT)
        val ConstDouble = BasicInstanceForge(Type.DoubleT)
        val ConstNothing = BasicInstanceForge(Type.Nothing)
        val ConstNull = BasicInstanceForge(Type.Null)
        val ConstNever = BasicInstanceForge(Type.Never)
        val ConstBoolTrue = BasicInstanceForge(Type.BoolTrue)
        val ConstBoolFalse = BasicInstanceForge(Type.BoolFalse)
        val ConstBool = BasicInstanceForge(Type.BoolUnknown)
        val Uninit = BasicInstanceForge(Type.UninitializedGeneric)

        fun make(type: Type): InstanceForge = BasicInstanceForge(type)
    }

    override fun clone(processes: MutableMap<UUID, InstanceForge>): InstanceForge

    fun referenceAll(references: MutableMap<UUID, List<InstanceAccessInstruction>>, prev: List<InstanceAccessInstruction>)

    fun compare(other: InstanceForge, instanceLookup: InstanceLookup): InstanceChange

    fun toTemplate(instanceLookup: InstanceLookup): InstanceBuilder

    fun mergeMut(others: List<InstanceForge>): InstanceForge

    fun reference()

    suspend fun drop(droppingHistory: History)
}




