package com.language.lookup.oxide


fun<K, V, T> Map<K, V>.lazyTransform(transformation: (K, V) -> T): Map<K, T> = LazyMap(this, transformation)


class LazyMap<K, V, T>(
    private val base: Map<K, V>,
    private val transformation: (K, V) -> T
) : Map<K, T> {

    private val cache = mutableMapOf<K, T>()

    override val entries: Set<Map.Entry<K, T>>
        get() = base.entries.map {
            object : Map.Entry<K, T> {
                override val key: K = it.key
                override val value: T
                    get() = getOrTransform(it.key)
            }
        }.toSet()

    override val keys: Set<K>
        get() = base.keys

    override val size: Int
        get() = base.size

    override val values: Collection<T>
        get() = base.keys.map { getOrTransform(it) }

    override fun isEmpty(): Boolean = base.isEmpty()

    override fun get(key: K): T? = base[key]?.let { getOrTransform(key) }

    override fun containsValue(value: T): Boolean = values.contains(value)

    override fun containsKey(key: K): Boolean = key in base

    private fun getOrTransform(key: K): T {
        return cache.getOrPut(key) {
            transformation(key, base.getValue(key))
        }
    }
}