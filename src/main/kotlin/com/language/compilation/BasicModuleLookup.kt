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

package com.language.compilation

import com.language.*
import com.language.Function
import com.language.compilation.templatedType.scope

data class BasicModuleLookup(
    val current: Module,
    override val localName: SignatureString,
    val modules: Map<SignatureString, Module>,
    val externalJars: ClassLoader, override val containerName: SignatureString
) : ModuleLookup {
    override fun localGetFunction(name: String): Function? = current.children[name] as? Function

    override val localSymbols: Map<String, ModuleChild>
        get() = current.children

    override fun withNewContainer(newContainerName: SignatureString): ModuleLookup {
        return BasicModuleLookup(current, localName, modules, externalJars, newContainerName)
    }

    override fun getImport(name: String): SignatureString? {
        return current.imports[name]
    }

    override val localImports: Map<String, SignatureString>
        get() = current.imports

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
    override fun hasType(name: SignatureString): Boolean {
        return current.children[name.value] is TypeDef || nativeModule(name.modName)?.children?.get(name.structName) is TypeDef
    }

    private fun withModule(signatureString: SignatureString) = BasicModuleLookup(modules[signatureString]!!, signatureString, modules, externalJars, containerName)

    override fun unwindType(name: SignatureString, tp: TemplatedType.Complex): TemplatedType {
        current.children[name.value]?.takeIf { it is TypeDef }?.let { typedef ->
            typedef as TypeDef
            val gens = typedef.generics.zip(tp.generics).associate { (gen, tp) -> gen.first to tp }
            return typedef.type.scope(gens).populate(this)
        }
        nativeModule(name.modName)?.children?.get(name.structName)?.takeIf { it is TypeDef }?.let { typedef ->
            typedef as TypeDef
            val gens = typedef.generics.zip(tp.generics).associate { (gen, tp) -> gen.first to tp }
            return typedef.type.scope(gens).populate(withModule(name.modName))
        }

        error("Type $name not found")
    }

}
