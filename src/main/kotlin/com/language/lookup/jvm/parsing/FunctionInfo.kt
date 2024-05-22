package com.language.lookup.jvm.parsing

import com.language.TemplatedType
import com.language.compilation.SignatureString
import com.language.compilation.Type

data class FunctionInfo(
    val genericDefinitions: List<GenericTypeParameter>,
    val args: List<TemplatedType>,
    val returnType: TemplatedType,
    val annotations: Set<AnnotationInfo>
)

data class GenericTypeParameter(val name: String, val bounds: List<SignatureString>)
