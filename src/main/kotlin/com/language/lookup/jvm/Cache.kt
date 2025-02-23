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