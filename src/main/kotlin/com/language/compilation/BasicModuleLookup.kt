package com.language.compilation

import com.language.Function
import com.language.Module
import com.language.ModuleChild
import com.language.Struct

data class BasicModuleLookup(
    val current: Module,
    override val localName: SignatureString,
    val modules: Map<SignatureString, Module>,
    val externalJars: ClassLoader
) : ModuleLookup {
    override fun localGetFunction(name: String): Function? = current.children[name] as? Function

    override val localSymbols: Map<String, ModuleChild>
        get() = current.children

    override fun localGetSymbol(name: String): ModuleChild? = current.children[name]

    override fun hasModule(name: SignatureString): Boolean =
        //if a native module with the name exists
        name == localName ||
        name in modules ||
                //if a java class with the name exists
                runCatching { externalJars.loadClass(name.toDotNotation()) }.isSuccess

    override fun hasStruct(name: SignatureString): Boolean {
        if (current.children[name.value] is Struct)
            return true


        val mod = nativeModule(name.modName) ?: return false
        return mod.children[name.structName] is Struct
    }

    override fun hasLocalStruct(name: SignatureString): Boolean = current.children[name.value] is Struct

    override fun nativeModule(name: SignatureString): Module? = modules[name]

}
