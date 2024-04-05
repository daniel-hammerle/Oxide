package com.language.dynamicCodeGen

import com.language.MathOp
import com.language.compilation.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class CodeGenerator {
    private val writer = ClassWriter(0)

    fun compileModule(module: IRModule): ByteArray {
        writer.newClass(module.name.replace("::", "/"))
        writer.visit(
            49,
            Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
            module.name.split("::").last(),
            module.name.replace("::", "/"),
            "java/lang/Object",
            null
        )
        module.functions.forEach { (name, func) ->compileFunction(func, name)  }
        return writer.toByteArray()
    }

    private fun compileFunction(function: IRFunction, name: String) {
        val mv = writer.visitMethod(
            Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC,
            name,
            generateFunctionSignature(function),
            null,
            arrayOf()
        )
        mv.visitMaxs(20, 100 + 1) // 1 for the instance
        compileInstruction(mv, function.body)
        mv.visitInsn(Opcodes.ARETURN)
    }

    private fun compileInstruction(mv: MethodVisitor, instruction: Instruction) {
        when(instruction) {
            is Instruction.DynamicCall -> {
                //load instance for dot call onto stack
                compileInstruction(mv, instruction.parent)
                //load method name
                mv.visitLdcInsn(instruction.name)
                //load args onto stack
                mv.visitLdcInsn(instruction.args.size)
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
                instruction.args.forEachIndexed { i, it ->
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitIntInsn(Opcodes.BIPUSH, i)
                    compileInstruction(mv, it)
                    mv.visitInsn(Opcodes.AASTORE)
                }
                //call dynamic dispatch
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/language/Calling",
                    "callDynamicDispatch",
                    "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",
                    false
                )
            }
            is Instruction.If -> {
                //load condition
                compileInstruction(mv, instruction.cond)
                //check if true
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/language/Logic",
                    "isTrue",
                    "(Ljava/lang/Object;)Z",
                    false
                )
                val betweenBodyAndElseBody = Label()
                val afterElseBody = Label()
                //if the condition is false (eq to 0) then jump to else body
                mv.visitJumpInsn(Opcodes.IFEQ, betweenBodyAndElseBody)
                //body
                compileInstruction(mv, instruction.body)
                mv.visitJumpInsn(Opcodes.GOTO, afterElseBody)
                //register label between body and else body
                mv.visitLabel(betweenBodyAndElseBody)
                compileInstruction(mv, instruction.elseBody ?: Instruction.Null)
                mv.visitLabel(afterElseBody)
            }
            is Instruction.LoadVar -> mv.visitVarInsn(Opcodes.ALOAD, 0)
            is Instruction.Math -> {
                compileInstruction(mv, instruction.first)
                compileInstruction(mv, instruction.second)

                val name = when(instruction.op) {
                    MathOp.Add -> "add"
                    MathOp.Sub -> "subtract"
                    MathOp.Mul -> "multiply"
                    MathOp.Div -> "divide"
                }

                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/language/Arithmatic",
                    name,
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    false
                )
            }
            is Instruction.ModuleCall -> {
                //load args
                instruction.args.forEach { compileInstruction(mv, it) }
                //call the appropriate method
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    instruction.moduleName.replace("::", "/"),
                    instruction.name,
                    "(${"Ljava/lang/Object;".repeat(instruction.args.size)})Ljava/lang/Object;",
                    false
                )
            }
            is Instruction.Pop -> mv.visitInsn(Opcodes.POP)
            is Instruction.DynamicPropertyAccess -> {
                //load instance to stack
                compileInstruction(mv, instruction.parent)
                //load field name
                mv.visitLdcInsn(instruction.name)
                //call dynamic access
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/language/Calling",
                    "dynamicPropertyAccess",
                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                    false
                )
            }
            is Instruction.StaticPropertyAccess -> {
                mv.visitLdcInsn(instruction.parentName.replace("::", "/"))
                mv.visitLdcInsn(instruction.name)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/language/Calling",
                    "staticPropertyAccess",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
                    false
                )
            }
            is Instruction.MultiInstructions -> instruction.instructions.forEach { compileInstruction(mv, it) }
            is Instruction.StaticCall -> {
                //load class name onto stack
                mv.visitLdcInsn(instruction.classModuleName.replace("::", "."))
                //load method name onto stack
                mv.visitLdcInsn(instruction.name)
                //load args onto stack
                mv.visitLdcInsn(instruction.args.size)
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
                instruction.args.forEachIndexed { i, it ->
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitLdcInsn(i)
                    compileInstruction(mv, it)
                    mv.visitInsn(Opcodes.AASTORE)
                }
                //invoke dynamic dispatch
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/language/Calling",
                    "callStaticDynamicDispatch",
                    "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",
                    false
                )
            }
            is Instruction.While -> TODO()
            is Instruction.Null -> mv.visitInsn(Opcodes.ACONST_NULL)
            is Instruction.StoreVar -> {
                compileInstruction(mv, instruction.value)
                mv.visitVarInsn(Opcodes.ASTORE, 0)
            }
            is Instruction.LoadConstBool -> {
                mv.visitInsn(if (instruction.value) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Boolean",
                    "valueOf",
                    "(Z)Ljava/lang/Boolean;",
                    false
                )
            }
            is Instruction.LoadConstDouble -> {
                mv.visitLdcInsn(instruction.value)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "valueOf",
                    "(D)Ljava/lang/Double;",
                    false
                )
            }
            is Instruction.LoadConstInt -> {
                mv.visitIntInsn(Opcodes.BIPUSH, instruction.value)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Integer",
                    "valueOf",
                    "(I)Ljava/lang/Integer;",
                    false
                )
            }
            is Instruction.LoadConstString -> mv.visitLdcInsn(instruction.value)
            is Instruction.Comparing -> TODO()
            else -> TODO()
        }
    }
}

private fun generateFunctionSignature(function: IRFunction): String {
    return "(${"Ljava/lang/Object;".repeat(10)})Ljava/lang/Object;"
}