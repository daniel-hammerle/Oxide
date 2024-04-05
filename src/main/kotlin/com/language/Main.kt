package com.language

import com.language.codegen.compileCheckedFunction
import com.language.codegen.compileProject
import com.language.compilation.*
import com.language.lexer.lexCode
import com.language.parser.parse
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun main() {

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

    val project = compileProject(irLookup)
    createZipFile(project.mapKeys { it.key.value }, "out.jar")
    /*
    val execution = ExecutionClassLoader(extensionClassLoader, ClassLoader.getPlatformClassLoader()).apply {
        putClassFile(bytes, "main")
    }


    execution.execute("main", "main")


     */

}

fun createZipFile(entries: Map<String, ByteArray>, zipFilePath: String) {
    val outputStream = FileOutputStream(zipFilePath)
    val zipOutputStream = ZipOutputStream(outputStream)

    try {
        entries.forEach { (fileName, data) ->
            val entry = ZipEntry(fileName.replace("::", "/") + ".class")
            zipOutputStream.putNextEntry(entry)
            zipOutputStream.write(data)
            zipOutputStream.closeEntry()
        }
    } finally {
        zipOutputStream.close()
        outputStream.close()
    }
}