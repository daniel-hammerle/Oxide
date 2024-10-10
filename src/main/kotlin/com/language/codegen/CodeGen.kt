package com.language.codegen

import com.language.compilation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

suspend fun compileProject(modules: Set<IRModule>): Map<SignatureString, ByteArray> = coroutineScope {
    val mutex = Mutex()
    val entries = mutableMapOf<SignatureString, ByteArray>()
    modules.map { module ->
        launch {
            val modPath = module.name
            val (modCode, structCode, implBlocks, lambdas)  = compileModule(module)
            mutex.withLock {
                if (modCode != null)
                    entries[modPath] = modCode
                for ((structPath, bytes) in structCode) {
                    entries[modPath + structPath] = bytes
                }
                for((blockPath, block) in implBlocks) {
                    entries[blockPath] = block
                }
                for((lambdaPath, bytes) in lambdas) {
                    entries[lambdaPath] = bytes
                }
            }
        }

    }.joinAll()

    entries
}

data class CompiledModuleOutput(
    val modCode: ByteArray?,
    val structs: Map<String, ByteArray>,
    val impls: Map<SignatureString, ByteArray>,
    val lambdaClasses: Map<SignatureString, ByteArray>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CompiledModuleOutput

        if (!modCode.contentEquals(other.modCode)) return false
        if (structs != other.structs) return false
        if (impls != other.impls) return false

        return true
    }

    override fun hashCode(): Int {
        var result = modCode.contentHashCode()
        result = 31 * result + structs.hashCode()
        result = 31 * result + impls.hashCode()
        return result
    }
}

suspend fun compileModule(module: IRModule): CompiledModuleOutput = coroutineScope {
    val structs = module.structs.map { (name, struct) ->
        async {
            compileStruct(module.name, name, struct)?.let { name to it }
        }
    }

    val lambdas = module.lambdas.mapValues { (_, lambda) ->
        async {
            compileLambda(lambda)
        }
    }

    val impls = module.implBlocks.entries.flatMap { (_, impls) ->
        impls.map { impl ->
            impl.fullSignature to async { compileImpl(impl) }
        }
    }

    val cw = ClassWriter(0)

    cw.visit(
        62,
        Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
        module.name.toJvmNotation(),
        null,
        "java/lang/Object",
        null
    )

    var isUsed = false

    module.functions.flatMap { (name, function) ->
        function.keepBlocks.forEach { (name, type) ->
            cw.visitField(
                Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE,
                name,
                type.toJVMDescriptor(),
                null,
                null
            )
        }
        function.checkedVariants().map { (argTypes, body) ->
            isUsed =true
            compileCheckedFunction(cw, name, body.first, body.second, argTypes)
        }
    }


    return@coroutineScope CompiledModuleOutput(
        if (isUsed) cw.toByteArray() else null,
        structs.mapNotNull { it.await() }.toMap(),
        impls.mapNotNull { it.second.await()?.let { a -> it.first to a } }.toMap(),
        lambdas.mapValues { it.value.await() }
    )
}


