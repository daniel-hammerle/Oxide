package com.language.compilation

import com.language.codegen.*
import com.language.compilation.tracking.InstanceForge
import com.language.compilation.tracking.InstanceTemplate
import com.language.compilation.tracking.join
import com.language.compilation.variables.allEqual
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

interface FunctionCandidate {
    val oxideArgs: List<Type>
    val jvmArgs: List<Type>
    val jvmReturnType: Type
    val oxideReturnType: Type
    val returnForge: InstanceForge
    val invocationType: Int
    val jvmOwner: SignatureString
    val name: String
    val jvmName: String
    val obfuscateName: Boolean
    val requireDispatch: Boolean
    val varargInfo: Pair<Int, Type>?
    val castReturnType: Boolean
        get() = false
    val requiresCatch: Set<SignatureString>

    fun generateCall(mv: MethodVisitor, stackMap: StackMap)
}

data class UnionFunctionCandidate(
    val candidates: Map<Type, FunctionCandidate>
) : FunctionCandidate {
    override val oxideArgs: List<Type>
        get() {
            return candidates.values
                .map { it.oxideArgs }
                .reduce { acc, types ->
                    acc.zip(types).map { (accType, newType) -> accType.join(newType)  }
                }
        }

    override val jvmName: String
        get() = TODO("Not yet implemented")

    override val jvmArgs: List<Type>
        get() {
            return candidates.values
                .map { it.jvmArgs }
                .reduce { acc, types ->
                    acc.zip(types).map { (accType, newType) -> accType.join(newType)  }
                }
        }
    override val jvmReturnType: Type
        get() {
            return candidates.values
                .map { it.jvmReturnType }
                .reduce { acc, type -> acc.join(type) }
        }
    override val oxideReturnType: Type
        get() {
            return candidates.values
                .map { it.oxideReturnType }
                .reduce { acc, type -> acc.join(type) }
        }
    override val returnForge: InstanceForge = candidates.map { (_, can) -> can.returnForge  }.reduce { acc, forge -> acc.join(forge) }
    override val invocationType: Int
        get() = error("No single invocation kind exists")
    override val jvmOwner: SignatureString
        get() = error("No single jvm owner exists")
    override val name: String
        get() = candidates.values.first().name
    override val obfuscateName: Boolean
        get() = error("No single owner exists")
    override val requireDispatch: Boolean
        get() = false
    override val varargInfo: Pair<Int, Type>?
        get() = null
    override val requiresCatch: Set<SignatureString> = candidates.values.flatMap { it.requiresCatch }.toSet()


    private val unifiedReturnType = candidates.values.map { it.oxideReturnType }.allEqual()

    override fun generateCall(mv: MethodVisitor, stackMap: StackMap) {
        mv.dynamicDispatch(candidates.keys.map { it.asBoxed() }, oxideReturnType, stackMap) { type ->
            val unboxed = type.asUnboxedOrIgnore()
            val item = if (unboxed is Type.BoolT) {
                candidates[unboxed] ?: candidates[Type.BoolFalse] ?: candidates[Type.BoolTrue]!!
            } else candidates[unboxed]!!
            item.generateCall(mv, stackMap)
            if (!unifiedReturnType) {
                boxOrIgnore(mv, type)
            }
        }
    }

}

data class SimpleFunctionCandidate(
    override val oxideArgs: List<Type>,
    override val jvmArgs: List<Type>,
    override val jvmReturnType: Type,
    override val oxideReturnType: Type,
    override val invocationType: Int,
    override val jvmOwner: SignatureString,
    override val name: String,
    override val jvmName: String,
    override val obfuscateName: Boolean,
    override val requireDispatch: Boolean,
    override val castReturnType: Boolean = false,
    val isInterface: Boolean = false,
    override val varargInfo: Pair<Int, Type>? = null,
    override val requiresCatch: Set<SignatureString> = emptySet(),
    override val returnForge: InstanceForge,

) : FunctionCandidate {

    override fun generateCall(mv: MethodVisitor, stackMap: StackMap) {
        val wrapInCatch = requiresCatch.isNotEmpty()
        val tryBegin = Label()
        val tryEnd = Label()
        val catch = Label()
        val end = Label()
        if (wrapInCatch) {
            mv.visitLabel(tryBegin)
        }
        mv.visitMethodInsn(
            invocationType,
            jvmOwner.toJvmNotation(),
            jvmName,
            this.toJvmDescriptor(),
            isInterface
        )
        if (jvmReturnType.isUnboxedPrimitive() && !oxideReturnType.isUnboxedPrimitive()) {
            boxOrIgnore(mv, jvmReturnType)
        }

        if (castReturnType) {
            //were boxing the return type because the only reason we cast the return type is when we have generics,
            // and then It's automatically always boxed
            mv.visitTypeInsn(Opcodes.CHECKCAST, oxideReturnType.asBoxed().toActualJvmType().toJvmName())
        }
        if (oxideReturnType.isUnboxedPrimitive() && !jvmReturnType.isUnboxedPrimitive()) {
            unboxOrIgnore(mv, oxideReturnType.asBoxed(), oxideReturnType)
        }

        if (wrapInCatch) {
            mv.visitLabel(tryEnd)
            mv.visitJumpInsn(Opcodes.GOTO, end)
            mv.visitLabel(catch)
            stackMap.push(Type.BasicJvmType(SignatureString("java::lang::Exception")))
            stackMap.generateFrame(mv)
            mv.visitJumpInsn(Opcodes.GOTO, end)

            stackMap.pop()
            mv.visitLabel(end)
            stackMap.push(oxideReturnType)
            stackMap.generateFrame(mv)
            stackMap.pop()

            requiresCatch.forEach {
                mv.visitTryCatchBlock(tryBegin, tryEnd, catch, it.toJvmNotation())
            }
        }
    }

}
fun Type.toJvmName(): String = when(this) {
    is Type.Array -> this.toJVMDescriptor()
    else -> this.toJVMDescriptor().removePrefix("L").removeSuffix(";")
}

fun FunctionCandidate.toJvmDescriptor() = generateJVMFunctionSignature(jvmArgs, jvmReturnType, varargInfo)

val FunctionCandidate.hasGenericReturnType: Boolean
    get() = jvmReturnType != oxideReturnType