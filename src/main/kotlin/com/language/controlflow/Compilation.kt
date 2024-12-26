package com.language.controlflow

import com.language.Module
import com.language.compilation.*
import com.language.createZipFile
import com.language.lexer.MetaInfo
import com.language.lexer.lexCode
import com.language.lookup.IRModuleLookup
import com.language.lookup.jvm.CachedJvmLookup
import com.language.lookup.oxide.BasicOxideLookup
import com.language.parser.ParseException
import com.language.parser.parse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(DelicateCoroutinesApi::class)
fun getCoroutineScope(): CoroutineScope {
    val dispatcher = newFixedThreadPoolContext(Runtime.getRuntime().availableProcessors(), "Compilation")
    val scope = CoroutineScope(dispatcher)
    return scope
}

interface FileTree {
    suspend fun appendFile(path: SignatureString, absolutePath: String, contents: String)
}

data class SourceFileInfo(val absolutePath: String, val content: String)

class FileTreeImpl : FileTree {
    val mutex = Mutex()

    val files: MutableMap<SignatureString, SourceFileInfo> = mutableMapOf()

    override suspend fun appendFile(path: SignatureString, absolutePath: String, contents: String) {
        mutex.withLock {
            files[path] = SourceFileInfo(absolutePath, contents)
        }
    }

    suspend fun get(path: SignatureString) = mutex.withLock { files[path] }

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

    val tree = FileTreeImpl()

    val (modules, parsingTime) = measureTime {
        val modules = parseDir(File(path), scope, tree)
        modules.mapValues {
            it.value.await().fold(
                onSuccess = { mod -> mod },
                onFailure = { e ->
                    e as ParseException
                    runBlocking {
                        printErrorMessage(
                            it.key,
                            tree.get(it.key)!!,
                            e.info,
                            e.message!!,
                            e.kind,
                            MessageMagnitude.Error
                        )
                    }
                    throw e
                }
            )
        }
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
            entryPoint.inferTypes(emptyList(), irLookup, emptyMap(), BasicHistory())
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


fun firstStageCompilation(
    module: Module,
    name: SignatureString,
    modules: Map<SignatureString, Module>,
    loader: ExtensionClassLoader
): IRModule {
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


fun parseDir(dir: File, scope: CoroutineScope, fileTree: FileTree): Map<SignatureString, Deferred<Result<Module>>> =
    mutableMapOf<SignatureString, Deferred<Result<Module>>>().apply {
        parseDir(dir, scope, fileTree, null, this)
    }

fun parseDir(
    dir: File,
    scope: CoroutineScope,
    fileTree: FileTree,
    currentPath: SignatureString?,
    map: MutableMap<SignatureString, Deferred<Result<Module>>>
) {
    if (!dir.isDirectory) error("`${dir.path}` is not a directory.")
    dir.listFiles()!!.forEach { file ->
        val fileName = SignatureString(file.name.split(".")[0])
        val combinedPath = currentPath?.let { it + fileName } ?: fileName
        when {
            file.isDirectory -> parseDir(file, scope, fileTree, combinedPath, map)
            file.isFile -> map[combinedPath] = scope.async { parseFile(file, combinedPath, fileTree) }
            else -> error("Invalid filetype $file")
        }
    }
}

suspend fun parseFile(file: File, path: SignatureString, fileTree: FileTree): Result<Module> = coroutineScope {
    if (!file.isFile) error("`${file.path}` is not a file.")
    val sourceCode = withContext(Dispatchers.IO) { file.readText() }
    val job = launch {
        fileTree.appendFile(path, file.absolutePath, sourceCode)
    }
    val result = lexCode(sourceCode)
    try {
        val module = parse(result)
        Result.success(module)
    } catch (e: ParseException) {
        Result.failure(e)
    } finally {
        job.join()
    }
}

enum class MessageKind {
    Syntax,
    Type,
    Logic
}

object Ansi {
    val Reset = "\u001B[0m"
    val Bold = "\u001B[1m"
    val Red = "\u001B[31m"
    val Gray = "\u001B[38;5;240m"
    val Green = "\u001B[32m"
    val Yellow = "\u001B[33m"
    val Blue = "\u001B[34m"
}

enum class MessageMagnitude(val color: String) {
    Error(Ansi.Red),
    Warning(Ansi.Yellow),
    Info(Ansi.Gray)
}

fun printErrorMessage(
    path: SignatureString,
    file: SourceFileInfo,
    info: MetaInfo,
    message: String,
    kind: MessageKind,
    magnitude: MessageMagnitude
) {
    val start = file.content.lastIndexOf('\n', info.start - 1) + 1
    val end = file.content.indexOf('\n', info.start + info.length)

    val lineCount = file.content.slice(0..info.start).count { it == '\n' } + 1

    println("${path.toJvmNotation()}.oxide:$lineCount:${info.start - start} ${magnitude.color}${kind.name.lowercase()}-${magnitude.name.lowercase()}${Ansi.Reset}: ${Ansi.Bold}$message${Ansi.Reset}")
    print(file.content.substring(start, info.start))
    print(magnitude.color)
    print(file.content.substring(info.start, info.start + info.length + 1))
    print(Ansi.Reset)
    if (info.start + info.length != end)
        print(file.content.substring(info.start + info.length + 1, end))
    println()

    repeat((info.start - start)) { print(" ") }
    repeat(info.length + 1) { print("^") }
    println()

}

inline fun <T> measureTime(task: () -> T): Pair<T, Long> {
    val before = Instant.now()
    val value = task()
    return value to before.until(Instant.now(), ChronoUnit.MILLIS)
}

