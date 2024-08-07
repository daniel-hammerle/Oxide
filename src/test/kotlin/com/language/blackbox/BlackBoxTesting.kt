package com.language.blackbox

import com.language.controlflow.compileCOde
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
fun DebugOutput.out(expected: String): DebugOutput {
    assertEquals(expected, stdOut)
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
            clazz.methods.first { it.name == "main_1" }!!.invoke(null)
        }

    }
    return DebugOutput(stdOut, returnValue)
}