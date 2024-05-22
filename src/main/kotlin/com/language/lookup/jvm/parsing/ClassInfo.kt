package com.language.lookup.jvm.parsing

import com.language.TemplatedType
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifiers

data class ClassInfo(
    val modifiers: Modifiers,
    val signature: SignatureString,
    val constructors: Set<FunctionInfo>,
    val generics: List<GenericTypeParameter>,
    val fields: Map<String, TemplatedType>,
    val interfaces: Set<SignatureString>,
    val staticFields: Map<String, Type>,
    val methods: Map<String, Set<FunctionInfo>>,
    val associatedFunctions: Map<String, Set<FunctionInfo>>,
)