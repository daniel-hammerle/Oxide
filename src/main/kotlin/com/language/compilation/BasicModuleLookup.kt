package com.language.compilation

import com.language.Function
import com.language.Module
import com.language.ModuleChild

data class BasicModuleLookup(
    val current: Module,
    override val localName: String,
    val modules: Map<String, Module>,
    val externalJars: ClassLoader
) : ModuleLookup {
    override fun localGetFunction(name: String): Function? = current.children[name] as? Function

    override val localSymbols: Map<String, ModuleChild>
        get() = current.children

    override fun localGetSymbol(name: String): ModuleChild? = current.children[name]

    override fun hasModule(name: String): Boolean =
        //if a native module with the name exists
        name in modules ||
                //if a java class with the name exists
                runCatching { externalJars.loadClass(name.replace("::", ".")) }.isSuccess

    override fun nativeModule(name: String): Module? = modules[name]

}
