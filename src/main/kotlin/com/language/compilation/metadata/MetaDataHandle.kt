package com.language.compilation.metadata

import com.language.compilation.Instruction
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import org.objectweb.asm.Label

interface MetaDataHandle : LambdaAppender{
    fun issueReturnTypeAppend(type: Type)

    fun appendKeepBlock(name: String, type: Type)

    val inlinableLambdas: List<TypedInstruction.Lambda>

    val inheritedGenerics: Map<String, Type>

    val returnLabel: Label?
}