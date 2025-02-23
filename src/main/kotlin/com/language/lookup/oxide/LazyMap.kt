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