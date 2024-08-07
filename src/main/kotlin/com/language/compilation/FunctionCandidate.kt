package com.language.compilation

import com.language.codegen.*
import com.language.compilation.variables.allEqual
import com.language.lexer.Token
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

interface FunctionCandidate {
    val oxideArgs: List<Type>
    val jvmArgs: List<Type>
    val jvmReturnType: Type
    val oxideReturnType: Type
    val invocationType: Int
    val jvmOwner: SignatureString
    val name: String
    val obfuscateName: Boolean
    val requireDispatch: Boolean
    val castReturnType: Boolean
        get() = false

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

    private val unifiedReturnType = candidates.values.map { it.oxideReturnType }.allEqual()

    override fun generateCall(mv: MethodVisitor, stackMap: StackMap) {
        mv.dynamicDispatch(candidates.keys, oxideReturnType, stackMap) { type ->
            candidates[type]!!.generateCall(mv, stackMap)
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
    override val obfuscateName: Boolean,
    override val requireDispatch: Boolean,
    override val castReturnType: Boolean = false
) : FunctionCandidate {

    override fun generateCall(mv: MethodVisitor, stackMap: StackMap) {
        mv.visitMethodInsn(
            invocationType,
            jvmOwner.toJvmNotation(),
            if (obfuscateName) jvmName(name, oxideArgs) else name,
            this.toJvmDescriptor(),
            invocationType == Opcodes.INVOKEINTERFACE
        )
        if (castReturnType) {
            //were boxing the return type because the only reason we cast the return type is when we have generics,
            // and then It's automatically always boxed
            mv.visitTypeInsn(Opcodes.CHECKCAST, oxideReturnType.asBoxed().toJVMDescriptor().removePrefix("L").removeSuffix(";"))
        }
    }

}


fun FunctionCandidate.toJvmDescriptor() = generateJVMFunctionSignature(jvmArgs, jvmReturnType)

val FunctionCandidate.hasGenericReturnType: Boolean
    get() = jvmReturnType != oxideReturnType