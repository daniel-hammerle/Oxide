package com.language.compilation.metadata

import com.language.compilation.FunctionMetaData
import com.language.compilation.Type
import com.language.compilation.join

class FunctionMetaDataHandle : MetaDataHandle {
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

    fun toMetaData() = FunctionMetaData(returnType, varCount)
}