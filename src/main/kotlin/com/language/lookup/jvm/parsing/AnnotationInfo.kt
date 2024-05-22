package com.language.lookup.jvm.parsing

import com.language.compilation.SignatureString

data class AnnotationInfo(val signatureString: SignatureString, val values: Map<String, Any>)
