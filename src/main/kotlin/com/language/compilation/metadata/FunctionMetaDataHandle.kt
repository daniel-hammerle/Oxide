package com.language.compilation.metadata

import com.language.compilation.*

class FunctionMetaDataHandle(override val inheritedGenerics: Map<String, Type>, private val appender: LambdaAppender) : MetaDataHandle {
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

    override suspend fun addLambda(
        argNames: List<String>,
        captures: Map<String, Type>,
        body: Instruction,
        generics: Map<String, Type>,
        imports: Set<SignatureString>
    ): SignatureString {
        return appender.addLambda(argNames, captures, body, generics, imports)
    }

    fun toMetaData() = FunctionMetaData(returnType, varCount)
}