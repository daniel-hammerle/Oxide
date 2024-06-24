package com.language.controlflow

import com.language.Module
import com.language.codegen.compileModule
import com.language.compilation.*
import com.language.createZipFile
import com.language.lexer.lexCode
import com.language.lookup.IRModuleLookup
import com.language.lookup.jvm.CachedJvmLookup
import com.language.lookup.oxide.BasicOxideLookup
import com.language.parser.parse
import kotlinx.coroutines.*
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.concurrent.timerTask

@OptIn(DelicateCoroutinesApi::class)
fun compileProject(fileName: String) {
    val (extensionClassLoader, compilationTime) = measureTime {
        val (tokens, lexingTime) = measureTime {
            lexCode(File(fileName).readText())

        }

        println("> Lexing took ${lexingTime}ms")

        val (module, parsingTime) = measureTime {
            parse(tokens)
        }
        println("> Parsing took ${parsingTime}ms")

        val dispatcher = newFixedThreadPoolContext(Runtime.getRuntime().availableProcessors(), "Compilation")
        val scope = CoroutineScope(dispatcher)

        val runtimeLib = "./RuntimeLib/build/libs/RuntimeLib-1.0-SNAPSHOT.jar"
        val extensionClassLoader = ExtensionClassLoader(runtimeLib, ClassLoader.getSystemClassLoader())
        val lookup = BasicModuleLookup(module, SignatureString("main"), mapOf(SignatureString("main") to module), extensionClassLoader, SignatureString("main"))

        val result = compile(lookup)
        val irLookup =  IRModuleLookup(CachedJvmLookup(extensionClassLoader), BasicOxideLookup(mapOf(result.name to result), emptyMap()))

        val (type, typeCheckingTime) = measureTime {
            runBlocking {
                scope.async {
                    (result.functions["main"]!! as BasicIRFunction).inferTypes(listOf(), irLookup, emptyMap())
                }.await()
            }
        }
        println("> Type-Inference took ${typeCheckingTime}ms")

        val (project, compilationTime) = measureTime {
            runBlocking {
                scope.async {
                    com.language.codegen.compileProject(setOf(result))
                }.await()
            }
        }

        println("> Compilation took ${compilationTime}ms")

        val (_, writingTime) = measureTime {
            for ((name, bytes) in project.entries) {
                File("out/${name.toJvmNotation()}.class").apply {
                    parentFile.mkdirs()
                    createNewFile()
                }.writeBytes(bytes)
            }
            createZipFile(project.mapKeys { it.key.value }, "out.jar")
        }

        println("> Writing took ${writingTime}ms")
        println("Finished Writing to File✅")
        extensionClassLoader
    }

    println("Full Compilation process took ${compilationTime}ms")

    ExtensionClassLoader("out.jar", extensionClassLoader)
        .loadClass("main")
        .methods.first { it.name == "main_1" }
        .invoke(null)
}


@OptIn(DelicateCoroutinesApi::class)
fun getCoroutineScope(): CoroutineScope {
    val dispatcher = newFixedThreadPoolContext(Runtime.getRuntime().availableProcessors(), "Compilation")
    val scope = CoroutineScope(dispatcher)
    return scope
}

fun compileAndWriteDir(path: String, externalLibs: List<String>) {
    val (_, compilationTime) = measureTime {

        val project = runBlocking { compileDir(path, externalLibs) }
        val (_, writingTime) = measureTime {
            for ((name, bytes) in project.entries) {
                File("out/${name.toJvmNotation()}.class").apply {
                    parentFile.mkdirs()
                    createNewFile()
                }.writeBytes(bytes)
            }
            createZipFile(project.mapKeys { it.key.value }, "out.jar")
        }

        println("> [stage 4] Writing took ${writingTime}ms")
        println("Finished Writing to File✅")
    }
    println("Full Compilation process took ${compilationTime}ms")
}

suspend fun compileDir(path: String, externalLibs: List<String>): Map<SignatureString, ByteArray> {
    val scope = getCoroutineScope()

    val deferredLoader = scope.async {
        loadExternalLibs(externalLibs)
    }

    val (modules, parsingTime) = measureTime {
        val modules = parseDir(File(path), scope)
        modules.mapValues { it.value.await() }
    }

    println("> [stage 0] Parsing finished in ${parsingTime}ms")

    val loader = deferredLoader.await()

    val (firstStageModules, firstStageTime) = measureTime {
        val future = modules.mapValues { (name, mod) ->
            scope.async { firstStageCompilation(mod, name, modules, loader) }
        }
        future.mapValues { (_, mod) -> mod.await() }
    }

    println("> [stage 1] finished in ${firstStageTime}ms")

    val irLookup = IRModuleLookup(CachedJvmLookup(loader), BasicOxideLookup(firstStageModules, emptyMap()))

    //use default entry point for now
    val entryPoint = firstStageModules[SignatureString("main")]!!.functions["main"]!! as BasicIRFunction

    val (_, secondStageCompileTime) = measureTime {
        scope.async {
            entryPoint.inferTypes(emptyList(), irLookup, emptyMap())
        }.await()
    }

    println("> [stage 2] inference finished in ${secondStageCompileTime}ms")

    val (project, codeGenTime) = measureTime {
        runBlocking {
            scope.async {
                com.language.codegen.compileProject(firstStageModules.values.toSet())
            }.await()
        }
    }

    println("> [stage 3] codegen finished in ${codeGenTime}ms")
    return project
}


fun firstStageCompilation(module: Module, name: SignatureString, modules: Map<SignatureString, Module>, loader: ExtensionClassLoader): IRModule {
    val lookup = BasicModuleLookup(
        module,
        name,
        modules,
        loader,
        name
    )

    return compile(lookup)
}

fun loadExternalLibs(externalLibs: List<String>): ExtensionClassLoader {
    val loader = ExtensionClassLoader(ClassLoader.getSystemClassLoader())
    externalLibs.forEach {
        loader.loadClassesFromJar(it)
    }
    return loader
}

fun parseDir(dir: File, scope: CoroutineScope): Map<SignatureString, Deferred<Module>> {
    val map = mutableMapOf<SignatureString, Deferred<Module>>()
    if (!dir.isDirectory) error("`${dir.path}` is not a directory.")
    dir.listFiles()!!.forEach { file ->
        val fileName = SignatureString(file.name.split(".")[0])
        when {
            file.isDirectory ->map.putAll(parseDir(file, scope).mapKeys { fileName + it.key })
            file.isFile -> map[fileName] = scope.async { parseFile(file) }
            else -> error("Invalid filetype $file")
        }
    }
    return map
}

fun parseFile(file: File): Module {
    if (!file.isFile) error("`${file.path}` is not a file.")
    val result = lexCode(file.readText())
    val module = parse(result)
    return module
}

inline fun<T> measureTime(task: () -> T): Pair<T, Long> {
    val before = Instant.now()
    val value = task()
    return value to before.until(Instant.now(), ChronoUnit.MILLIS)
}