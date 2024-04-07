package com.language

import com.language.codegen.compileProject
import com.language.compilation.*
import com.language.lexer.lexCode
import com.language.parser.parse
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.collections.HashMap


fun main() {
    execute()
}

fun execute() {
    val tokens = lexCode(File("language.lang").readText())
    val module = parse(tokens)

    val runtimeLib = "./RuntimeLib/build/libs/RuntimeLib-1.0-SNAPSHOT.jar"
    val extensionClassLoader = ExtensionClassLoader(runtimeLib)
    val modules = extensionClassLoader.createModuleTree()
    val lookup = BasicModuleLookup(module, SignatureString("main"), modules, ClassLoader.getPlatformClassLoader())

    val result = compile(lookup)
    val irLookup =  BasicIRModuleLookup(setOf(result), extensionClassLoader)

    val type = result.functions["main"]!!.type(listOf(), irLookup)
    println(type)
    val map = HashMap<Int, String>()

    val project = compileProject(irLookup)


    for ((name, bytes) in project.entries) {
        File("out/${name.toJvmNotation()}.class").apply {
            parentFile.mkdirs()
            createNewFile()
        }.writeBytes(bytes)
    }
    createZipFile(project.mapKeys { it.key.value }, "out.jar")

    ExtensionClassLoader(
        "out.jar"
    )
        .loadClass("main")
        .methods.first { it.name == "main" }
        .invoke(null)
    /*
    val execution = ExecutionClassLoader(extensionClassLoader, ClassLoader.getPlatformClassLoader()).apply {
        putClassFile(bytes, "main")
    }


    execution.execute("main", "main")


     */

}

fun createZipFile(entries: Map<String, ByteArray>, zipFilePath: String) {
    val outputStream = FileOutputStream(zipFilePath)
    val jarOutputStream = JarOutputStream(outputStream)

    try {
        entries.forEach { (fileName, data) ->
            val entry = JarEntry(fileName.replace("::", "/") + ".class")
            jarOutputStream.putNextEntry(entry)
            jarOutputStream.write(data)
            jarOutputStream.closeEntry()
        }
        jarOutputStream.putNextEntry(JarEntry("META-INF/MANIFEST.MF"));
        jarOutputStream.write(("Manifest-Version: 1.0\n".encodeToByteArray()))
    } finally {
        jarOutputStream.close()
        outputStream.close()
    }
}