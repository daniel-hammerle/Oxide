package com.language.codegen

import com.language.compilation.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

fun compileProject(lookup: IRModuleLookup): Map<String, ByteArray> {
    val entries = mutableMapOf<String, ByteArray>()
    lookup.nativeModules.forEach { module ->
        val modPath = module.name
        val (modCode, structCode)  = compileModule(module, lookup)
        entries[modPath] = modCode
        for ((structPath, bytes) in structCode) {
            entries["$modPath::$structPath"] = bytes
        }
    }

    return entries
}

fun compileModule(module: IRModule, lookup: IRModuleLookup):  Pair<ByteArray, Map<String, ByteArray>> {
    val cw = ClassWriter(0)
    cw.visit(
        49,
        Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
        module.name.split("::").last(),
        module.name.replace("::", "/"),
        "java/lang/Object",
        null
    )
    module.functions.forEach { (name, function) ->
        function.checkedVariants.forEach { (argTypes, _) ->
            compileCheckedFunction(cw, function, name, lookup, argTypes)
        }
    }
    val structs = module.structs.mapValues { (name, struct) -> compileStruct(module.name, name, struct) }
    return cw.toByteArray() to structs
}


