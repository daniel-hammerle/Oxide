package com.language.compilation.metadata

import com.language.compilation.FunctionMetaData
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.join

class FunctionMetaDataHandle(override val inheritedGenerics: Map<String, Type>) : MetaDataHandle {
    lateinit var returnType: Type
        private set

    var varCount: Int = 0

    fun hasReturnType() = ::returnType.isInitialized

    override fun issueReturnTypeAppend(type: Type) {
        returnType = if (::returnType.isInitialized) {
            returnType.join(type)
        } else {
            type
        }
    }

    val keepBlocks: MutableMap<String, Type> = mutableMapOf()

    override fun appendKeepBlock(name: String, type: Type) {
        keepBlocks[name] = type
    }

    fun toMetaData() = FunctionMetaData(returnType, varCount)
}