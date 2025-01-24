package com.language.compilation.metadata

import com.language.compilation.*
import com.language.compilation.tracking.InstanceForge
import org.objectweb.asm.Label

interface MetaDataHandle : LambdaAppender{
    fun issueReturnTypeAppend(type: InstanceForge)

    fun appendKeepBlock(name: String, type: Type)

    val inlinableLambdas: List<TypedInstruction.Lambda>

    val inheritedGenerics: Map<String, Type>

    val returnLabel: Label?
}


interface MetaDataTypeHandle {
    fun returnTypeAppend(type: Type.Broad)
    val inheritedGenerics: Map<String, Type>

}

class MetaDataTypeHandleImpl(type: Type.Broad? = null, override val inheritedGenerics: Map<String, Type>): MetaDataTypeHandle {
    var type: Type.Broad? = type
        private set

    override fun returnTypeAppend(type: Type.Broad) {
        this.type = this.type?.join(type) ?: type
    }
}