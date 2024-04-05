package com.language.compilation

import com.language.Function
import com.language.Module
import com.language.ModuleChild

interface ModuleLookup {
    fun localGetFunction(name: String): Function?
    fun localGetSymbol(name: String): ModuleChild?

    val localName: String

    val localSymbols: Map<String, ModuleChild>

    fun hasStruct(name: String): Boolean
    fun hasLocalStruct(name: String): Boolean
    fun hasModule(name: String): Boolean
    fun nativeModule(name: String): Module?


}