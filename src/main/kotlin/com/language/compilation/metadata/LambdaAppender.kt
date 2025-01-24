package com.language.compilation.metadata

import com.language.compilation.Instruction
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.tracking.InstanceForge

interface LambdaAppender {

    suspend fun addLambda(
        argNames: List<String>,
        captures: Map<String, InstanceForge>,
        body: Instruction,
        generics: Map<String, Type>,
        imports: Set<SignatureString>,
    ): SignatureString
}