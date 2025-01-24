package com.language.blackbox

import com.language.compilation.*
import com.language.controlflow.measureTime
import com.language.lexer.lexCode
import com.language.lookup.IRModuleLookup
import com.language.lookup.jvm.CachedJvmLookup
import com.language.lookup.oxide.BasicOxideLookup
import com.language.parser.parse
import kotlinx.coroutines.*
import org.jetbrains.annotations.Contract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import kotlin.test.assertEquals


class CustomClassLoader(
    private val classData: Map<String, ByteArray>
) : ClassLoader() {

    override fun findClass(name: String): Class<*> {
        val classBytes = classData[name]
            ?: throw ClassNotFoundException("Class not found: $name")
        return defineClass(name, classBytes, 0, classBytes.size)
    }
}

inline fun<T> captureOut(closure: () -> T): Pair<String, T> {
    val originalOut = System.out

    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream)

    System.setOut(printStream)

    try {
        val result = closure()
        printStream.flush()
        val capturedOutput = outputStream.toString()
        return capturedOutput to result
    } finally {
        // Restore the original System.out
        System.setOut(originalOut)
    }
}

inline fun<T> customStdIn(input: String, closure: () -> T): T {
    val inputStream: InputStream = ByteArrayInputStream(input.toByteArray())
    val oldIn = System.`in`
    System.setIn(inputStream)
    try {
        return closure()
    } finally {
        System.setIn(oldIn)
    }
}


data class DebugOutput(val stdOut: String, val returnValue: Any?)

@Contract("_ -> this")
fun DebugOutput.out(vararg expected: String): DebugOutput {
    assert(stdOut in expected)
    return this
}

@Contract("_ -> this")
inline fun DebugOutput.out(closure: (s: String) -> Boolean): DebugOutput {
    assert(closure(stdOut))
    return this
}

@Contract("_ -> this")
fun DebugOutput.returnValue(expected: Any?): DebugOutput {
    assertEquals(expected, returnValue)
    return this
}


fun runCode(code: String, input: String = ""): DebugOutput {
    val project = compileCOde(code)
    val loader = CustomClassLoader(project.mapKeys { it.key.toDotNotation() })


    val (stdOut, returnValue) = captureOut {
        customStdIn(input) {
            val clazz = loader.loadClass("main")
            clazz.methods.first { it.name == "main_0" }.invoke(null)
        }

    }
    return DebugOutput(stdOut, returnValue)
}


@OptIn(DelicateCoroutinesApi::class)
fun compileCOde(code: String): Map<SignatureString, ByteArray> {
    val (project, compilationTime) = measureTime {
        val (tokens, lexingTime) = measureTime {
            lexCode(code)

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
                    (result.functions["main"]!! as BasicIRFunction).inferTypes(listOf(), irLookup, emptyMap(), BasicHistory())
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

        project
    }

    println("Full Compilation process took ${compilationTime}ms")
    return project
}
