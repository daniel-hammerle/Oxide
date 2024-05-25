package com.language.compilation.metadata

import com.language.compilation.IRModule
import com.language.compilation.Instruction
import com.language.compilation.SignatureString
import com.language.compilation.Type

class LambdaAppenderImpl(
    var module: IRModule?
): LambdaAppender {
    override suspend fun addLambda(
        argNames: List<String>,
        captures: Map<String, Type>,
        body: Instruction,
        generics: Map<String, Type>,
        imports: Set<SignatureString>
    ): SignatureString {
        return module?.addLambda(argNames, captures, body, generics, imports)!!
    }

}