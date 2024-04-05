package com.language.compilation

import com.language.Function
import com.language.Module
import com.language.ModuleChild
import com.language.Struct

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

    override fun hasStruct(name: String): Boolean {
        if (current.children[name] is Struct)
            return true
        val structName = name.split("::").last()
        val modName = name.removeSuffix("::$structName")

        val mod = nativeModule(modName) ?: return false
        return mod.children[structName] is Struct
    }

    override fun hasLocalStruct(name: String): Boolean = current.children[name] is Struct

    override fun nativeModule(name: String): Module? = modules[name]

}
