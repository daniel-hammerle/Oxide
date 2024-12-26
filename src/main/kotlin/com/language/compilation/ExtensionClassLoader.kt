package com.language.compilation

import com.language.Expression
import com.language.Function
import com.language.Module
import com.language.ModuleChild
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.modifiers.modifiers
import com.language.lexer.MetaInfo
import com.language.lookup.jvm.RawClassLoader
import com.language.lookup.jvm.rep.toTemplatedType
import com.language.lookup.jvm.toType
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.jar.JarEntry
import java.util.jar.JarFile


class ExtensionClassLoader(
    private val parent: ClassLoader
) : RawClassLoader(null) {

    private val loadedClasses: MutableMap<String, Pair<Class<*>,ByteArray>> = mutableMapOf()

    constructor(jarPath: String, parent: ClassLoader) : this(parent) {
        loadClassesFromJar(jarPath)

    }

    fun loadClassesFromJar(jarFilePath: String) {
        try {
            val jarFile = JarFile(jarFilePath)
            jarFile.stream().forEach { entry: JarEntry ->
                if (entry.name.endsWith(".class")) {
                    val className = entry.name.replace("/", ".").removeSuffix(".class")

                    val classBytes: ByteArray = jarFile.getInputStream(entry).readAllBytes()
                    val clazz = defineClass(className, classBytes, 0, classBytes.size)
                    loadedClasses[className] = clazz to classBytes
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    override fun getBytes(name: String): ByteArray? = loadedClasses[name]?.second

    override fun loadClass(name: String): Class<*> {
        val name = SignatureString.fromDotNotation(name)
        return loadedClasses[name.toDotNotation()]?.first ?: parent.loadClass(name.toDotNotation())
    }

    override fun findClass(name: String): Class<*> {
        val name = SignatureString.fromDotNotation(name)
        return loadedClasses[name.toDotNotation()]?.first  ?: parent.loadClass(name.toDotNotation())
    }
}
