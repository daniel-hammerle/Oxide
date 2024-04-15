package com.language.codegen

import com.language.compilation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

suspend fun compileProject(lookup: IRModuleLookup): Map<SignatureString, ByteArray> = coroutineScope {
    val mutex = Mutex()
    val entries = mutableMapOf<SignatureString, ByteArray>()
    lookup.nativeModules.map { module ->
        launch {
            val modPath = module.name
            val (modCode, structCode)  = compileModule(module)
            mutex.withLock {
                entries[modPath] = modCode
                for ((structPath, bytes) in structCode) {
                    entries[modPath + structPath] = bytes
                }
            }
        }

    }.joinAll()

    entries
}

suspend fun compileModule(module: IRModule):  Pair<ByteArray, Map<String, ByteArray>> = coroutineScope {
    val cw = ClassWriter(0)
    cw.visit(
        62,
        Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
        module.name.toJvmNotation(),
        null,
        "java/lang/Object",
        null
    )

    module.functions.flatMap { (name, function) ->
        function.checkedVariantsUniqueJvm().map { (argTypes, body) ->
            compileCheckedFunction(cw, name, body.first,body.second, argTypes)
        }
    }

    val structs = module.structs.mapValues { (name, struct) ->
        async {
            compileStruct(module.name, name, struct)
        }
    }.mapValues { it.value.await() }
    return@coroutineScope cw.toByteArray() to structs
}


