package com.language.compilation

import com.language.Expression
import com.language.Function
import com.language.Module
import com.language.ModuleChild
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.jar.JarEntry
import java.util.jar.JarFile


class ExtensionClassLoader(
    jarPath: String,
    private val parent: ClassLoader
) : ClassLoader() {

    private val loadedClasses: MutableMap<String, Class<*>> = mutableMapOf()

    init {
        loadClassesFromJar(jarPath)
    }

    private fun loadClassesFromJar(jarFilePath: String) {
        try {
            val jarFile = JarFile(jarFilePath)
            jarFile.stream().forEach { entry: JarEntry ->
                if (entry.name.endsWith(".class")) {
                    val className = entry.name.replace("/", ".").removeSuffix(".class")

                    val classBytes: ByteArray = jarFile.getInputStream(entry).readAllBytes()
                    val clazz = defineClass(className, classBytes, 0, classBytes.size)
                    loadedClasses[className] = clazz
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun createModuleTree(): Map<SignatureString, Module> = loadedClasses
        .map { (name, clazz) -> SignatureString.fromDotNotation(name) to clazz.toModule() }
        .toMap()


    override fun loadClass(name: String): Class<*> {
        val name = SignatureString.fromDotNotation(name)
        return loadedClasses[name.toDotNotation()] ?: parent.loadClass(name.toDotNotation())
    }

    override fun findClass(name: String): Class<*> {
        val name = SignatureString.fromDotNotation(name)
        return loadedClasses[name.toDotNotation()]  ?: parent.loadClass(name.toDotNotation())
    }
}

private fun Class<*>.toModule(): Module {
    val children: MutableMap<String, ModuleChild> = mutableMapOf()
    declaredMethods
        .filter { Modifier.isStatic(it.modifiers) }
        .forEach { method ->
            val function = Function(method.parameters.map { it.name }, Expression.UnknownSymbol("external"))
            children[method.name] = function
        }

    return Module(children, emptyMap(), emptyMap())
}
