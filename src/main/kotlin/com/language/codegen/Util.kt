package com.language.codegen

import com.language.compilation.*
import com.language.compilation.tracking.BroadForge
import com.language.compilation.tracking.InstanceForge
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.*

data class Box<T>(val item: T)

fun<T : BroadForge> List<T>.cloneAll(): List<T> {
    val map = mutableMapOf<UUID, InstanceForge>()
    return map { it.clone(map) as T }
}



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


fun unboxOrIgnore(mv: MethodVisitor, type: Type, target: Type)  {
    when  {
        type == Type.Int && target is Type.IntT -> mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Integer",
            "intValue",
            "()I",
            false
        )
        type == Type.Double && target is Type.DoubleT -> mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Double",
            "doubleValue",
            "()D",
            false
        )
        type == Type.Bool && target is Type.BoolT -> mv.visitMethodInsn(
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

fun generateJVMFunctionSignature(argTypes: Iterable<Type>, returnType: Type, varargInfo: Pair<Int, Type>? = null): String {
    val info = varargInfo?.let { "[" + it.second.toJVMDescriptor() } ?: ""
    val args = argTypes
        .take((varargInfo?.first ?: argTypes.count()))
        .joinToString(separator = "") {
            it.toActualJvmType().toJVMDescriptor()
        }
    return "(${args}${info})${returnType.toJVMDescriptor()}"
}

fun Type.toJVMDescriptor(): String = when(this) {
    Type.DoubleT -> "D"
    Type.IntT -> "I"
    is Type.BoolT -> "Z"
    is Type.JvmType -> "L${signature.toJvmNotation()};"
    is Type.Lambda -> "L${signature.toJvmNotation()};"
    Type.Nothing, Type.Never -> "V"
    Type.UninitializedGeneric -> "Ljava/lang/Object;"
    is Type.JvmArray -> "[${this.itemType.toJVMDescriptor()}"
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

fun<A, B> List<A>.lazyMap(transform: (A) -> B): List<B> = LazyList(this, transform)

class LazyList<A, B>(
    val original: List<A>,
    val transform: (A) -> B
) : List<B> {
    override val size: Int
        get() = original.size

    override fun contains(element: B): Boolean = original.any { transform(it) == element }

    override fun containsAll(elements: Collection<B>): Boolean = elements.all { contains(it) }

    override fun get(index: Int): B = original[index].let(transform)

    override fun indexOf(element: B): Int = original.indexOfFirst { transform(it) == element }

    override fun isEmpty(): Boolean = original.isEmpty()

    override fun iterator(): Iterator<B> = listIterator()

    override fun lastIndexOf(element: B): Int = original.indexOfLast { transform(it) == element }

    override fun listIterator(): ListIterator<B> = listIterator(0)

    override fun listIterator(index: Int) = object : ListIterator<B> {
        var currentIndex = index

        override fun hasNext(): Boolean = currentIndex < original.size
        override fun hasPrevious(): Boolean = currentIndex > 0
        override fun next(): B = original[currentIndex++].let(transform)
        override fun nextIndex(): Int = currentIndex
        override fun previous(): B = original[--currentIndex].let(transform)
        override fun previousIndex(): Int = currentIndex - 1
    }



    override fun subList(fromIndex: Int, toIndex: Int): List<B> = LazyList(
        original.subList(fromIndex, toIndex),
        transform
    )
}

