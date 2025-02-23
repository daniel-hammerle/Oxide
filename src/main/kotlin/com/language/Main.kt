// Copyright 2025 Daniel Hammerle
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
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
        .methods.first { it.name == "main_0" }
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