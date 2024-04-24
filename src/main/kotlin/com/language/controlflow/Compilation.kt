package com.language.controlflow

import com.language.compilation.*
import com.language.createZipFile
import com.language.lexer.lexCode
import com.language.parser.parse
import kotlinx.coroutines.*
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(DelicateCoroutinesApi::class)
fun compileProject(fileName: String) {
    val tokens = lexCode(File(fileName).readText())
    val module = parse(tokens)

    val dispatcher = newFixedThreadPoolContext(8, "Compilation")
    val scope = CoroutineScope(dispatcher)

    val runtimeLib = "./RuntimeLib/build/libs/RuntimeLib-1.0-SNAPSHOT.jar"
    val extensionClassLoader = ExtensionClassLoader(runtimeLib, ClassLoader.getSystemClassLoader())
    val modules = extensionClassLoader.createModuleTree()
    val lookup = BasicModuleLookup(module, SignatureString("main"), modules, ClassLoader.getPlatformClassLoader())

    val result = compile(lookup)
    val irLookup =  BasicIRModuleLookup(setOf(result), extensionClassLoader)

    val preTypeChecking = Instant.now()
    val type = runBlocking {
        scope.async {
            result.functions["main"]!!.inferTypes(listOf(), irLookup).type
        }.await()
    }
    println("Resulting Type: $type")
    println("Type-Inference took ${preTypeChecking.until(Instant.now(), ChronoUnit.MILLIS)}ms")

    val preCompilation = Instant.now()
    val project = runBlocking {
        scope.async {
            com.language.codegen.compileProject(irLookup)
        }.await()
    }

    println("Compilation took ${preCompilation.until(Instant.now(), ChronoUnit.MILLIS)}ms")

    for ((name, bytes) in project.entries) {
        File("out/${name.toJvmNotation()}.class").apply {
            parentFile.mkdirs()
            createNewFile()
        }.writeBytes(bytes)
    }
    createZipFile(project.mapKeys { it.key.value }, "out.jar")

    ExtensionClassLoader("out.jar", extensionClassLoader)
        .loadClass("main")
        .methods.first { it.name == "main_1" }
        .invoke(null)
}