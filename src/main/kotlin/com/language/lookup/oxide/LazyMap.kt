package com.language.lookup.oxide

import kotlinx.coroutines.runBlocking


fun<K, V, T> Map<K, V>.lazyTransform(transformation: (V) -> T): Map<K, T> = LazyMap(this, transformation)


class LazyMap<K, V, T>(private val base: Map<K, V>, private val transformation: (V) -> T): Map<K, T> {
    override val entries: Set<Map.Entry<K, T>>
        get() = error("Entries not accessible")
    override val keys: Set<K>
        get() = base.keys
    override val size: Int
        get() = base.size
    override val values: Collection<T>
        get() = base.values.map(transformation)

    override fun isEmpty(): Boolean = base.isEmpty()

    override fun get(key: K): T? = base[key]?.let(transformation)

    override fun containsValue(value: T): Boolean = value in values

    override fun containsKey(key: K): Boolean = key in base
}