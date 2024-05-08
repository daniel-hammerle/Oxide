package com.language


import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream




fun main() {
    com.language.controlflow.compileProject("error.oxide")
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