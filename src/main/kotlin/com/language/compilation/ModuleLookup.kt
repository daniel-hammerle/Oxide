package com.language.compilation

import com.language.Function
import com.language.Module
import com.language.ModuleChild
import com.language.TemplatedType

interface ModuleLookup {
    fun localGetFunction(name: String): Function?
    fun localGetSymbol(name: String): ModuleChild?

    val localName: SignatureString
    val containerName: SignatureString
    val localImports: Map<String, SignatureString>
    val localSymbols: Map<String, ModuleChild>

    fun withNewContainer(newContainerName: SignatureString): ModuleLookup

    fun getImport(name: String): SignatureString?

    fun hasStruct(name: SignatureString): Boolean
    fun hasLocalStruct(name: SignatureString): Boolean
    fun hasModule(name: SignatureString): Boolean
    fun nativeModule(name: SignatureString): Module?
    fun hasType(name: SignatureString): Boolean
    fun unwindType(name: SignatureString, tp: TemplatedType.Complex): TemplatedType
}