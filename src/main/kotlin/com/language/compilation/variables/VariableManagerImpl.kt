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

package com.language.compilation.variables

import com.language.codegen.VarFrame
import com.language.compilation.*
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.join
import com.language.compilation.tracking.mapValuesToMutable
import com.language.eval.NoopVariableMapping
import java.util.IdentityHashMap
import java.util.UUID


class VariableManagerImpl(
    override val parent: VariableMapping,
    override val variables: MutableMap<String, VariableProvider> = mutableMapOf(),
    private val inlineClosureFrames: MutableMap<SignatureString, Map<String, VariableProvider>> = mutableMapOf()
) : VariableManager {


    companion object {
        fun fromForges(items: List<Pair<String, InstanceForge>>): VariableManager {
            val mapping = VariableMappingImpl.empty()
            val variables: MutableMap<String, VariableProvider> = mutableMapOf()
            for ((name, forge) in items) {
                variables[name] = VariableBinding(mapping.new(forge.type), forge)
            }
            return VariableManagerImpl(mapping, variables)
        }
    }

    override fun loadVar(name: String): TypedInstruction {
        val provider = variables[name] ?: error("No variable with name $name")
        return provider.get(parent)
    }

    override fun changeVar(name: String, value: TypedInstruction): TypedInstruction {
        if (name in variables) {
            return variables[name]!!.put(value, parent)
        }

        val (ins, binding) = VariableBinding.forValue(value, parent)
        variables[name] = binding
        return ins
    }

    override fun toVarFrame(): VarFrame = parent.toVarFrame()

    override fun getType(name: String): Type = variables[name]?.type(parent) ?: error("No variable found with name `$name`")
    override fun getForge(name: String): InstanceForge {
        return variables[name]?.forge ?: error("No variable found with name `$name`")
    }

    override fun mapping(): VariableMapping = parent

    override fun loopMerge(postLoop: VariableManager, parentScope: VariableManager?): ScopeAdjustment {
        val previousAllocations = variables
            .mapNotNull { (it.value as? AllocationRecord)?.let { a -> if (! a.allocated) null else it.key}}
            .toSet()

        val postLoopAllocations = postLoop.variables
            .mapNotNull { (it.value as? AllocationRecord)?.let { a -> if (! a.allocated) null else it.key} }
            .toSet()

        val newAllocations = postLoopAllocations - previousAllocations

        val changeInstructions = mutableListOf<ScopeAdjustInstruction>()
        for (name in newAllocations) {
            if (!hasVar(name)) {
                //in this case, it was a local in that scope that was newly allocated,
                // and we don't need to preserve that, so we skip
                continue
            }

            //this means that in the current context it has to be const / semi const
            val newId = parent.new(getType(name))
            parentScope?.parent?.tryAllocateId(newId, getType(name))
            val storeIns = ScopeAdjustInstruction.Store(
                loadVar(name), // get the const-expr value
                newId
            )
            putVar(name, VariableBinding(newId, getForge(name)))
            parentScope?.putVar(name, VariableBinding(newId, getForge(name)))

            changeInstructions.add(storeIns)
        }

        return ScopeAdjustment(changeInstructions)
    }

    data class MergeVariable(
        val name: String,
        val nativeProv: VariableProvider,
        val providers: List<VariableProvider>,
        val commonForge: InstanceForge
    )

    override fun merge(branches: List<VariableManager>): List<ScopeAdjustment> {
        val commonVariables = variables.mapNotNull { (name, provider) ->
            val branchesProviders = branches.mapNotNull { it.variables[name]  }
            when(branchesProviders.size) {
                branches.size -> MergeVariable(name, provider, branchesProviders, provider.forge.mergeMut(branchesProviders.map { it.forge }))
                else -> null
            }
        }.filter { hasVar(it.name) }

        val changeInstructions = branches.map { mutableListOf<ScopeAdjustInstruction>() }

        mergeTypeConversions(changeInstructions, commonVariables)
        mergeAllocations(changeInstructions, commonVariables)

        return changeInstructions.map { ScopeAdjustment(it) }
    }

    private fun mergeTypeConversions(
        changeInstructions: List<MutableList<ScopeAdjustInstruction>>,
        commonVariables: List<MergeVariable>,
    ) {
        for ((_, _, providers, forge) in commonVariables) {
            for ((index, provider) in providers.withIndex()) {
                val id = provider.physicalId ?: continue

                //gracefully ignore type conversions on values that are not allocated
                //(since they don't have a type anyway)

                val provType = provider.forge.type
                if (forge.type is Type.Union) {
                    changeInstructions[index].add(ScopeAdjustInstruction.Box(id, provType))
                }

                if (forge.type.isUnboxedPrimitive() && !provType.isUnboxedPrimitive()) {
                    changeInstructions[index].add(ScopeAdjustInstruction.Unbox(id, provType))
                }
            }
        }
    }

    private fun mergeAllocations(
        changeInstructions: List<MutableList<ScopeAdjustInstruction>>,
        commonVariables: List<MergeVariable>
    ) {
        val requiredAllocationChanges = commonVariables.filter { (_, nativeProv, providers) ->
            //if the variable was allocated before branching, then the ids would be the same.
            //if both haven't been allocated, they would also be the same.
            //and if one of the branches is allocated, then there is a change to be made.
            providers.any { prov -> prov.physicalId != nativeProv.physicalId }
        }

        for ((name, nativeProv, providers, commonForge) in requiredAllocationChanges) {
            mergeAllocation(name, nativeProv, providers, changeInstructions, commonForge)
        }

    }

    private fun mergeAllocation(
        name: String,
        nativeProv: VariableProvider,
        providers: List<VariableProvider>,
        changeInstructions: List<MutableList<ScopeAdjustInstruction>>,
        commonForge: InstanceForge
    ) {
        val ids = providers.map { provider -> provider.physicalId }

        val nativeId = nativeProv.physicalId

        //true when we don't have an allocation on this scope but on all other scopes the same (or none)
        if (nativeId == null) {
            if (ids.allEqual()) {
                when(val commonId = ids.first()) {
                    is Int -> {
                        if (parent.tryAllocateId(commonId, commonForge.type)) {
                            variables[name] = VariableBinding(commonId, commonForge)
                            return
                        }
                    }
                    null -> {
                        //in this case, there hasn't been any allocation on any scope so far.
                        if (providers.size == 1) {
                            variables[name] = providers.first()
                        } else {
                            val newId = parent.new(commonForge.type)
                            variables[name] = VariableBinding(newId, commonForge)
                            generateAdjustOperation(ids, newId, commonForge.type, providers, changeInstructions)
                        }

                        return

                    }
                }
            }
            //now there is either no common id, or it is already allocated on this scope.
            //therefore, we force to migrate every scope to change to this
            val newId = parent.new(commonForge.type)
            variables[name] = VariableBinding(newId, commonForge)
            generateAdjustOperation(ids, newId, commonForge.type, providers, changeInstructions)
        } else {
            //the native scope seems to already have an allocation for that,
            // and it seems to have been dropped in a scope
            val newId = parent.new(commonForge.type)
            variables[name] = VariableBinding(newId, commonForge)
            generateAdjustOperation(ids, newId, commonForge.type, providers, changeInstructions)
        }

    }

    private fun generateAdjustOperation(
        ids: List<Int?>,
        newId: Int,
        commonType: Type,
        providers: List<VariableProvider>,
        changeInstructions: List<MutableList<ScopeAdjustInstruction>>
    ) {
        ids.forEachIndexed { index, id ->
            val ins = when(id) {
                null -> ScopeAdjustInstruction.Store(
                    providers[index].get(NoopVariableMapping), //get the constexpr value.
                    // (use noop mapping
                    // to prevent unwanted physical variable interaction)
                    newId
                )
                else -> ScopeAdjustInstruction.Move(id, newId, commonType).takeUnless { id == newId }
            }

            ins?.let { changeInstructions[index].add(ins) }
        }
    }

    override fun getTempVar(type: Type): TempVariable = parent.getTempVar(type)

    override fun deleteVar(name: String) {
        when (name in variables) {
            true -> variables.remove(name)?.delete(parent)
            false -> {}
        }
    }

    override fun hasVar(name: String): Boolean {
        return name in variables
    }

    override fun clone(): VariableManagerImpl {
        val map = mutableMapOf<UUID, InstanceForge>()
        val providerChanges = IdentityHashMap<VariableProvider, VariableProvider>()

        return VariableManagerImpl(
            parent.clone(),
            variables.mapValuesToMutable { (_, value) -> value.clone(map, providerChanges) },
            inlineClosureFrames.mapValuesToMutable { (_, value) -> value.mapValues { (_, prov) -> prov.clone(map, providerChanges) } }
        )
    }

    override fun putVar(name: String, provider: VariableProvider) {
        //if a bound variable with that name already exists, we simply delete it
        variables[name] = provider
    }

    override fun reference(newName: String, oldName: String) {
        val value = variables[oldName] ?: error("No variable with `$oldName` exists")
        variables[newName] = value
    }

    override fun change(name: String, forge: InstanceForge): Int {
        return variables[name]?.put(TypedInstruction.Noop(forge.type, forge), parent)?.let { (it as TypedInstruction.StoreVar).id } ?: run {
            val id = parent.new(forge.type)
            variables[name] = VariableBinding(id, forge)
            id
        }
    }


    override fun addInlineLambdaFrame(container: VariableManager, id: SignatureString, names: Iterable<String>) {
        inlineClosureFrames[id] = names.associateWith { container.variables[it]!! }
    }

    override fun reconstructInlineLambdaFrame(id: SignatureString): VariableManager {
        return VariableManagerImpl(parent, inlineClosureFrames[id]!!.toMutableMap(), inlineClosureFrames)
    }

    override fun preserverPrevious(): VariableManager {
        return VariableManagerImpl(parent, inlineClosureFrames = inlineClosureFrames)
    }


}
fun <T> List<T>.allEqual(): Boolean {
    if (this.isEmpty()) return true
    val first = this.first()
    return this.all { it == first }
}