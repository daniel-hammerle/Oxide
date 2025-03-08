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

import com.language.compilation.HistSlice
import com.language.compilation.History
import com.language.compilation.SignatureString
import com.language.compilation.Type
import java.util.UUID

class StructInstanceForge(
    val members: MutableMap<String, InstanceForge>,
    //maps the field name to the generic name (if field has generic type)
    val generics: Map<String, String>,
    val fullSignature: SignatureString,
    private val creationHistory: HistSlice,
    override val id: UUID = UUID.randomUUID(),
    private var referenceCount: Int = 1,
) : InstanceForge, GenericDestructableForge, MemberChangeable {


    override val type: Type.JvmType
        get() = buildType()

    override fun clone(processes: MutableMap<UUID, InstanceForge>): InstanceForge {
        return processes[id] ?: StructInstanceForge(
            members.mapValuesToMutable { (_, forge) -> forge.clone(processes) },
            generics,
            fullSignature,
            creationHistory,
            id,
            referenceCount,
        ).also { forge -> processes[id] = forge }
    }

    override fun reference() {
        assert(referenceCount > 0) { "Cannot reference a already destroyed object" }
        referenceCount++
    }

    override suspend fun drop(droppingHistory: History) {
        assert(referenceCount > 0)

        if (--referenceCount == 0) {
            //drop event
            members.forEach { (_, forge) -> forge.drop(droppingHistory) }

            val intersection = droppingHistory.intersectionPoint(creationHistory)
            intersection.func.addContainerAlloc(fullSignature, intersection.args) //place the container in the intersection point
        }

    }

    override fun referenceAll(references: MutableMap<UUID, List<InstanceAccessInstruction>>, prev: List<InstanceAccessInstruction>) {
        references[id] = prev
        members.forEach { it.value.referenceAll(references, prev + InstanceAccessInstruction.Property(it.key)) }
    }

    override fun compare(other: InstanceForge, instanceLookup: InstanceLookup): InstanceChange {
        if (other.id == id) {
            other as StructInstanceForge
            return InstanceChange.PropertyChanges(members.mapValues { (name, old) -> old.compare(other.members[name]!!, instanceLookup)})
        }
        if (other is JoinedInstanceForge && this in other.forges) {
            return InstanceChange.Unionization(other.forges.filter { it != this }.map { it.toTemplate(instanceLookup) })
        }

        return InstanceChange.New(other.toTemplate(instanceLookup))
    }

    override fun toTemplate(instanceLookup: InstanceLookup): InstanceBuilder {
        instanceLookup.find(id)?.let { return it }

        return InstanceBuilder.New(
            InstanceTemplate.Object(fullSignature, members.mapValues { (_, forge) -> forge.toTemplate(instanceLookup)  }, generics)
        )
    }

    override fun mergeMut(others: List<InstanceForge>): InstanceForge {
        val same = others.filter { it.id == id }.map { it as StructInstanceForge }
        for ((name, forge) in members) {
            val others= same.map { it.members[name]!! }
            members[name] = forge.mergeMut(others)
        }

        val foreign = others.filter { it.id != id }
        if (foreign.isEmpty()) return this

        return foreign.fold(this as InstanceForge) { acc, it -> acc.join(it) }
    }

    private fun buildType(): Type.JvmType {
        val genericTypes = members.associateNotNull { (name, forge) ->
            generics[name]?.let { it to forge.type }
        }.toMap()

        return Type.BasicJvmType(fullSignature, genericTypes)
    }

    fun memberForge(name: String): InstanceForge? {
        return members[name]
    }


    override fun destructGeneric(name: String): InstanceForge {
        val fieldName = generics.firstNotNullOf { (key, value) -> key.takeIf { value == name } }
        return memberForge(fieldName)!!
    }

    override suspend fun definiteChange(name: String, forge: InstanceForge, droppingHistory: History) {
        if (name !in members) {
            error("Struct does not have member $name")
        }
        forge.reference()
        members[name]!!.drop(droppingHistory)
        members[name] = forge
    }

    override fun possibleChange(name: String, forge: InstanceForge) {
        if (name !in members) {
            error("Struct does not have member $name")
        }

        forge.reference()
        members[name] = members[name]!!.join(forge)
    }

}

inline fun <A, B, C, D> Map<A, B>.associateNotNull(
    initCap: Int = size,
    closure: (
        Map.Entry<A, B>
    ) -> Pair<C, D>?
): Map<C, D> {
    val newMap = HashMap<C, D>(initCap)

    for (entry in this) closure(entry)?.let { newMap[it.first] = it.second }

    return newMap
}

inline fun <A, B, C> Map<A, B>.mapValuesToMutable(closure: (Map.Entry<A, B>) -> C): MutableMap<A, C> {
    val result = HashMap<A, C>(size)
    for (entry in this) result[entry.key] = closure(entry)
    return result
}