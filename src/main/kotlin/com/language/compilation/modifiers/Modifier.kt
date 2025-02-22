package com.language.compilation.modifiers

enum class Modifier(val position: Int) {
    Error(0),
    Public(1),
    Static(2),
    Typed(3),
    Inline(4),
    Extern(5),
    Intrinsic(6)
}