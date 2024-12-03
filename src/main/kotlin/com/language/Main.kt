package com.language


import com.language.compilation.ExtensionClassLoader
import com.language.controlflow.compileAndWriteDir
import com.language.controlflow.loadExternalLibs
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream


fun main() {
    val externalLibs = listOf("./RuntimeLib/build/libs/RuntimeLib-1.0-SNAPSHOT.jar")
    try {
        compileAndWriteDir("./oxide", externalLibs)
    } catch (e: Exception) {
        e.printStackTrace()
        return
    }
    val loader = loadExternalLibs(externalLibs)
    ExtensionClassLoader("out.jar", loader)
        .loadClass("main")
        .methods.first { it.name == "main_1" }
        .invoke(null)
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