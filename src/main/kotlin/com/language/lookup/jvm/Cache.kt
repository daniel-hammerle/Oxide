package com.language.lookup.jvm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Cache<K, V> {
    val map: MutableMap<K, V> = mutableMapOf()
    val mutex = Mutex()

    suspend fun get(key: K) = mutex.withLock { map[key] }

    suspend fun set(key: K, value: V) = mutex.withLock { map[key] = value }

    suspend fun contains(key: K) = mutex.withLock { key in map }

    suspend fun edit(closure: suspend (map: MutableMap<K, V>) -> Unit) = mutex.withLock { closure(map) }
}