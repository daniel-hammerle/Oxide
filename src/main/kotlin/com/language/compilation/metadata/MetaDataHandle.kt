package com.language.compilation.metadata

import com.language.compilation.Type

interface MetaDataHandle {
    fun issueReturnTypeAppend(type: Type)

    fun appendKeepBlock(name: String, type: Type)

    val inheritedGenerics: Map<String, Type>
}