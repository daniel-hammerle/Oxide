package com.language.controlflow

import com.language.compilation.*
import com.language.createZipFile
import com.language.lexer.lexCode
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

        val dispatcher = newFixedThreadPoolContext(8, "Compilation")
        val scope = CoroutineScope(dispatcher)

        val runtimeLib = "./RuntimeLib/build/libs/RuntimeLib-1.0-SNAPSHOT.jar"
        val extensionClassLoader = ExtensionClassLoader(runtimeLib, ClassLoader.getSystemClassLoader())
        val lookup = BasicModuleLookup(module, SignatureString("main"), mapOf(SignatureString("main") to module), extensionClassLoader)

        val result = compile(lookup)
        val irLookup =  BasicIRModuleLookup(setOf(result), extensionClassLoader)

        val (type, typeCheckingTime) = measureTime {
            runBlocking {
                scope.async {
                    result.functions["main"]!!.inferTypes(listOf(), irLookup)
                }.await()
            }
        }
        println("> Type-Inference took ${typeCheckingTime}ms")

        val (project, compilationTime) = measureTime {
            runBlocking {
                scope.async {
                    com.language.codegen.compileProject(irLookup)
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

inline fun<T> measureTime(task: () -> T): Pair<T, Long> {
    val before = Instant.now()
    val value = task()
    return value to before.until(Instant.now(), ChronoUnit.MILLIS)
}