package com.language.compilation.metadata

import com.language.compilation.Instruction
import com.language.compilation.SignatureString
import com.language.compilation.Type

interface MetaDataHandle : LambdaAppender{
    fun issueReturnTypeAppend(type: Type)

    fun appendKeepBlock(name: String, type: Type)


    val inheritedGenerics: Map<String, Type>
}