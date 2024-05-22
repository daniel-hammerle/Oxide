package com.language.lookup.jvm

import org.objectweb.asm.ClassReader

abstract class RawClassLoader(parent: ClassLoader?) : ClassLoader(parent) {
    abstract fun getBytes(name: String): ByteArray?

    fun getReader(name: String): ClassReader? = getBytes(name)?.let { ClassReader(it) }
}