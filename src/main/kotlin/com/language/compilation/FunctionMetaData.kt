package com.language.compilation

import com.language.compilation.tracking.InstanceBuilder
import com.language.compilation.tracking.InstanceChange

data class FunctionMetaData(
    val returnType: Type,
    val returnBuilder: InstanceBuilder,
    val args: List<InstanceChange>,
    val uniqueId: Int,
    val varCount: Int
)