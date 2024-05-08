package com.language.compilation.templatedType

import com.language.compilation.Type

sealed interface TypePopulationResult {
    data object Failure : TypePopulationResult

    data class Success(val generics: Map<String, Type>) : TypePopulationResult
}