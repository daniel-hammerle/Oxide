package com.language.compilation.variables

import com.language.codegen.VarFrame
import com.language.compilation.*
import com.language.eval.NoopVariableMapping

class VariableManagerImpl(
    override val parent: VariableMapping,
    override val variables: MutableMap<String, VariableProvider> = mutableMapOf()
) : VariableManager {

    override fun loadVar(name: String): TypedInstruction {
        val provider = variables[name]
        if (provider != null)
            return provider.get(parent)

        return parent.loadVar(name)
    }

    override fun changeVar(name: String, value: TypedInstruction): TypedInstruction {
        if (name in variables) {
            return variables[name]!!.put(value, parent)
        }

        variables[name] = VariableBinding(name)
        val id = parent.change(name, value.type)
        return TypedInstruction.StoreVar(id, value)
    }

    override fun change(name: String, type: Type): Int {
        return parent.change(name, type)
    }

    override fun toVarFrame(): VarFrame = parent.toVarFrame()

    override fun getType(name: String): Type = variables[name]?.type(parent) ?: parent.getType(name)
    override fun mapping(): VariableMapping = parent

    override fun genericChangeRequest(name: String, genericName: String, type: Type) {
        variables[name]?.genericChangeRequest(parent, genericName, type) ?: parent.genericChangeRequest(name, genericName, type)
    }

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
            val newId = parent.change(name, getType(name))
            parentScope?.parent?.tryAllocateId(newId, name, getType(name))
            val storeIns = ScopeAdjustInstruction.Store(
                loadVar(name), // get the const-expr value
                newId
            )
            putVar(name, VariableBinding(name))
            parentScope?.putVar(name, VariableBinding(name))



            changeInstructions.add(storeIns)
        }

        return ScopeAdjustment(changeInstructions)
    }

    override fun merge(branches: List<VariableManager>): List<ScopeAdjustment> {
        val commonVariables = variables.mapNotNull { (name, provider) ->
            val branchesProviders = branches.mapNotNull { it.variables[name]?.let { a -> a to it }  }
            when(branchesProviders.size) {
                branches.size -> Triple(name, provider, branchesProviders)
                else -> null
            }
        }.filter { hasVar(it.first) }

        val changeInstructions = branches.map { mutableListOf<ScopeAdjustInstruction>() }

        mergeTypeConversions(changeInstructions, commonVariables)
        mergeAllocations(changeInstructions, commonVariables)

        return changeInstructions.map { ScopeAdjustment(it) }
    }

    override fun realName(name: String): String {
        return when(val binding = variables[name]) {
            is VariableBinding -> {
                if (binding.name == name) name else realName(binding.name)
            }
            else -> name
        }
    }

    private fun mergeTypeConversions(
        changeInstructions: List<MutableList<ScopeAdjustInstruction>>,
        commonVariables: List<Triple<String, VariableProvider, List<Pair<VariableProvider, VariableManager>>>>,
    ) {
        for ((name, nativeProv, providers) in commonVariables) {
            val commonType = providers
                .map { (provider, variables) -> provider.type(variables.parent) }
                .reduce { acc, type -> acc.join(type) }
            for ((index, provider) in providers.withIndex()) {
                val id = provider.first.physicalName(provider.second.parent)?.let { provider.second.parent.getId(it) }

                //gracefully ignore type conversions on values that are not allocated
                //(since they don't have a type anyway)
                if (id == null) {
                    continue
                }

                val provType = provider.first.type(provider.second.parent)
                if (commonType is Type.Union) {
                    changeInstructions[index].add(ScopeAdjustInstruction.Box(id, provType))
                }

                if (commonType.isUnboxedPrimitive() && !provType.isUnboxedPrimitive()) {
                    changeInstructions[index].add(ScopeAdjustInstruction.Unbox(id, provType))
                }
            }
        }
    }

    private fun mergeAllocations(
        changeInstructions: List<MutableList<ScopeAdjustInstruction>>,
        commonVariables: List<Triple<String, VariableProvider, List<Pair<VariableProvider, VariableManager>>>>
    ) {
        val requiredAllocationChanges = commonVariables.filter { (_, nativeProv, providers) ->
            //if the variable was allocated before branching, then the names would be the same.
            //if both haven't been allocated, they would also be the same.
            //and if one of the branches is allocated, then there is a change to be made.
            providers.any { (prov, manager) -> prov.physicalName(manager.parent) == nativeProv.physicalName(parent) }
        }

        for ((name, nativeProv, providers) in requiredAllocationChanges) {
            mergeAllocation(name, nativeProv, providers, changeInstructions)
        }
    }

    private fun mergeAllocation(
        name: String,
        nativeProv: VariableProvider,
        providers: List<Pair<VariableProvider, VariableManager>>,
        changeInstructions: List<MutableList<ScopeAdjustInstruction>>
    ) {
        val ids = providers.map { (provider, variables) -> provider.physicalName(variables.parent)?.let { variables.parent.getId(it) }}

        val commonType = providers
            .map { (provider, variables) -> provider.type(variables.parent) }
            .reduce { acc, type -> acc.join(type) }

        val nativeId = nativeProv.physicalName(parent)?.let { parent.getId(it) }

        //true when we don't have an allocation on this scope but on all other scopes the same (or none)
        if (nativeId == null) {
            if (ids.allEqual()) {
                when(val commonId = ids.first()) {
                    is Int -> {
                        if (parent.tryAllocateId(commonId, name, commonType)) {
                            variables[name] = VariableBinding(name)
                            return
                        }
                    }
                    null -> {
                        //in this case, there hasn't been any allocation on any scope so far.
                        if (providers.size == 1) {
                            variables[name] = providers.first().first
                        } else {
                            val newId = parent.change(name, commonType)
                            variables[name] = VariableBinding(name)
                            generateAdjustOperation(ids, newId, commonType, providers, changeInstructions)
                        }

                        return

                    }
                }
            }
            //now there is either no common id, or it is already allocated on this scope.
            //therefore, we force to migrate every scope to change to this
            val newId = parent.change(name, commonType)
            variables[name] = VariableBinding(name)
            generateAdjustOperation(ids, newId, commonType, providers, changeInstructions)
        } else {
            //the native scope seems to already have an allocation for that,
            // and it seems to have been dropped in a scope
            val n = nativeProv.physicalName(parent)!!
            val newId = parent.change(n, commonType)
            variables[name] = VariableBinding(n)
            generateAdjustOperation(ids, newId, commonType, providers, changeInstructions)
        }

    }

    private fun generateAdjustOperation(
        ids: List<Int?>,
        newId: Int,
        commonType: Type,
        providers: List<Pair<VariableProvider, VariableManager>>,
        changeInstructions: List<MutableList<ScopeAdjustInstruction>>
    ) {
        ids.forEachIndexed { index, id ->
            val ins = when(id) {
                null -> ScopeAdjustInstruction.Store(
                    providers[index].first.get(NoopVariableMapping), //get the constexpr value.
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
            false -> parent.deleteVar(name)
        }
    }

    override fun hasVar(name: String): Boolean {
        return name in variables || parent.hasVar(name)
    }

    override fun clone(): VariableManagerImpl {
        return VariableManagerImpl(parent.clone(), variables.mapValues { (_, value) -> value.clone() }.toMutableMap())
    }

    override fun putVar(name: String, provider: VariableProvider) {
        //if a bound variable with that name already exists, we simply delete it
        if (name in variables) {
            variables.remove(name)?.delete(parent)
        }
        variables[name] = provider
    }

    override fun reference(newName: String, oldName: String) {
        val value = variables[oldName] ?: error("No variable with `$oldName` exists")
        variables[newName] = value
    }


}
fun <T> List<T>.allEqual(): Boolean {
    if (this.isEmpty()) return true
    val first = this.first()
    return this.all { it == first }
}