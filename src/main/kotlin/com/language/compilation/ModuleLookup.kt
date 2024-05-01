package com.language.compilation

import com.language.Function
import com.language.Module
import com.language.ModuleChild

interface ModuleLookup {
    fun localGetFunction(name: String): Function?
    fun localGetSymbol(name: String): ModuleChild?

    val localName: SignatureString
    val localImports: Map<String, SignatureString>
    val localSymbols: Map<String, ModuleChild>

    fun getImport(name: String): SignatureString?

    fun hasStruct(name: SignatureString): Boolean
    fun hasLocalStruct(name: SignatureString): Boolean
    fun hasModule(name: SignatureString): Boolean
    fun nativeModule(name: SignatureString): Module?


}