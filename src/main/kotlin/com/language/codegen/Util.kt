package com.language.codegen

import com.language.compilation.*
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

fun boxOrIgnore(mv: MethodVisitor, type: Type): Boolean {
    when(type) {
        Type.IntT -> mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Integer",
            "valueOf",
            "(I)Ljava/lang/Integer;",
            false
        )
        Type.DoubleT -> mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Double",
            "valueOf",
            "(D)Ljava/lang/Double;",
            false
        )
        is Type.BoolT -> mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Boolean",
            "valueOf",
            "(Z)Ljava/lang/Boolean;",
            false
        )
        else -> return true //gracefully ignore
    }
    return false
}


fun unboxOrIgnore(mv: MethodVisitor, type: Type)  {
    when (type) {
        Type.Int -> mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Integer",
            "intValue",
            "()I",
            false
        )
        Type.Double -> mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Double",
            "doubleValue",
            "()D",
            false
        )
        Type.Bool -> mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Boolean",
            "booleanValue",
            "()Z",
            false
        )
        else -> {}
    }
}


fun unbox(mv: MethodVisitor, type: Type)  {
    when (type) {
        Type.Int -> mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Integer",
            "intValue",
            "()I",
            false
        )
        Type.Double -> mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Double",
            "doubleValue",
            "()D",
            false
        )
        Type.Bool -> mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Boolean",
            "booleanValue",
            "()Z",
            false
        )
        else -> error("Invalid type to unbox")
    }
}

fun generateJVMFunctionSignature(argTypes: Iterable<Type>, returnType: Type): String {
    return "(${argTypes.joinToString(separator = "") { it.toActualJvmType().toJVMDescriptor() }})${returnType.toJVMDescriptor()}"
}

fun Type.toJVMDescriptor(): String = when(this) {
    Type.DoubleT -> "D"
    Type.IntT -> "I"
    is Type.BoolT -> "Z"
    is Type.JvmType -> "L${signature.toJvmNotation()};"
    is Type.Lambda -> "L${signature.toJvmNotation()};"
    Type.Nothing, Type.Never -> "V"
    is Type.JvmArray -> "[${if (this.itemType.getOrDefault(Type.Object).isUnboxedPrimitive()) itemType.getOrDefault(Type.Object).toJVMDescriptor() else Type.Object.toJVMDescriptor()}"
    //null has to be of some type, so we'll just make it object
    Type.Null -> "Ljava/lang/Object;"
    //unions will just be Objects
    is Type.Union -> "Ljava/lang/Object;"
}



fun<T> Iterable<T>.enumerate(): Iterable<Pair<Int, T>> {
    return object : Iterable<Pair<Int, T>> {
        override fun iterator(): Iterator<Pair<Int, T>> {
            return object : Iterator<Pair<Int, T>> {
                val iter = this@enumerate.iterator()
                var index: Int = 0
                override fun hasNext(): Boolean = iter.hasNext()
                override fun next(): Pair<Int, T> = index to iter.next().also { index++ }
            }
        }
    }
}

