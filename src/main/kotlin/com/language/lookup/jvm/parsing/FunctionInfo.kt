package com.language.lookup.jvm.parsing

import com.language.TemplatedType
import com.language.compilation.SignatureString

data class FunctionInfo(
    val genericDefinitions: List<GenericTypeParameter>,
    val args: List<TemplatedType>,
    val returnType: TemplatedType,
    val annotations: Set<AnnotationInfo>,
    val exceptionInfo: ExceptionInfo
)

data class ExceptionInfo(val exceptions: Set<SignatureString>, val others: Set<FunctionMention>)

data class FunctionMention(val owner: SignatureString, val name: String, val argTypes: List<TemplatedType>)

data class GenericTypeParameter(val name: String, val bounds: List<SignatureString>)
