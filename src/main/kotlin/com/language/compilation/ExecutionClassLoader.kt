package com.language.compilation

class ExecutionClassLoader(
    private val extensions: ExtensionClassLoader,
    private val system: ClassLoader
) : ClassLoader() {

    private val classes: MutableMap<String, Class<*>> = mutableMapOf()

    fun putClassFile(file: ByteArray, name: String) {
        classes[name] = defineClass(name, file, 0, file.size)
    }

    fun execute(name: String, function: String): Any? {
        return classes[name]!!.declaredMethods.first { it.name == function }.invoke(null)
    }

    override fun loadClass(name: String): Class<*> = classes[name] ?: runCatching { extensions.loadClass(name) }.getOrNull() ?: system.loadClass(name)

    override fun findClass(name: String): Class<*> = classes[name] ?: runCatching { extensions.loadClass(name) }.getOrNull() ?: system.loadClass(name)

}