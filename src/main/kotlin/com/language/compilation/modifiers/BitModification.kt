package com.language.compilation.modifiers

fun Int.setBit(position: Int) = this or (1 shl position)

fun Int.clearBit(position: Int) = this and (1 shl  position).inv()

fun Int.isSet(position: Int) = this and (1 shl position) != 0