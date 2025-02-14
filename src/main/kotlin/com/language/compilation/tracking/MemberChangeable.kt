package com.language.compilation.tracking

interface MemberChangeable {
    fun definiteChange(name: String, forge: InstanceForge)
    fun possibleChange(name: String, forge: InstanceForge)
}