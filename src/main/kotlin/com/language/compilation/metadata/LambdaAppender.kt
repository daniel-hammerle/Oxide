package com.language.compilation.metadata

import com.language.compilation.Instruction
import com.language.compilation.SignatureString
import com.language.compilation.Type

interface LambdaAppender {

    suspend fun addLambda(
        argNames: List<String>,
        captures: Map<String, Type>,
        body: Instruction,
        generics: Map<String, Type>,
        imports: Set<SignatureString>,
    ): SignatureString
}