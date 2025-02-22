package com.language.eval

import com.language.compilation.*
import com.language.compilation.metadata.MetaDataHandle
import com.language.compilation.templatedType.matchesSubset
import com.language.compilation.variables.SemiConstBinding
import com.language.compilation.variables.VariableManager
import com.language.lookup.IRLookup

object NotConstMatchableException : Exception() {
    private fun readResolve(): Any = NotConstMatchableException
}

//Matches const values against a pattern at compile-time
suspend fun matches(
    pattern: IRPattern,
    value: TypedInstruction.Const,
    variables: VariableManager,
    lookup: IRLookup,
    handle: MetaDataHandle,
    hist: History
): Boolean {
    return when(pattern) {
        is IRPattern.Binding -> {
            variables.putVar(pattern.name, SemiConstBinding(value, value.forge))
            true
        }
        is IRPattern.Condition -> {
            if (!matches(pattern.parent, value, variables, lookup, handle, hist)) return false
            val result = pattern.condition.inferTypes(variables, lookup, handle, hist)
            if (!result.isEffectivelyConst()) {
                throw NotConstMatchableException
            }

            result.effectiveConstValue()!!.value() == true
        }
        is IRPattern.Destructuring -> {
            if (!pattern.type.matchesSubset(value.type, generics = mutableMapOf(), modifiers = mutableMapOf(), lookup = lookup))
                return false
            value as TypedInstruction.ConstObject
            return pattern.patterns.zip(value.fields).all {
                matches(it.first, it.second.second, variables, lookup, handle, hist)
            }
        }
    }
}