package com.language.codegen

import com.language.CompareOp
import com.language.MathOp
import com.language.compilation.*
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes


fun compileInstruction(mv: MethodVisitor, instruction: Instruction, variables: VariableMapping, lookup: IRModuleLookup) {
    when(instruction) {
        is Instruction.DynamicCall -> compileDynamicCall(mv, instruction, variables, lookup)
        is Instruction.DynamicPropertyAccess -> {
            val returnType = instruction.type(variables, lookup)
            val parentType = instruction.parent.type(variables, lookup)
            //load instance onto the stack
            compileInstruction(mv, instruction.parent, variables, lookup)
            mv.visitFieldInsn(
                Opcodes.GETFIELD,
                parentType.toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                instruction.name,
                returnType.toJVMDescriptor()
            )
        }
        is Instruction.If -> {
            //eval condition
            compileInstruction(mv, instruction.cond, variables, lookup)
            val betweenBodyAndElseBody = Label()
            val afterElseBody = Label()
            val elseBodyType = instruction.elseBody?.type(variables, lookup) ?: Type.Null
            val bodyType = instruction.body.type(variables, lookup)
            //if the condition is false goto else
            mv.visitJumpInsn(Opcodes.IFEQ, betweenBodyAndElseBody)
            //body
            compileInstruction(mv, instruction.body, variables, lookup)
            if (bodyType != elseBodyType) {
                boxOrIgnore(mv, bodyType)
            }
            //skip else body when body was executed
            mv.visitJumpInsn(Opcodes.GOTO, afterElseBody)
            //here is the between body and else body label
            mv.visitLabel(betweenBodyAndElseBody)
            //else body
            compileInstruction(mv, instruction.elseBody ?: Instruction.Null, variables, lookup)
            if (bodyType != elseBodyType) {
                boxOrIgnore(mv, elseBodyType)
            }
            //label after else body
            mv.visitLabel(afterElseBody)
        }
        is Instruction.LoadConstBool -> when(instruction.value) {
            true -> mv.visitInsn(Opcodes.ICONST_1)
            false -> mv.visitInsn(Opcodes.ICONST_0)
        }
        is Instruction.LoadConstDouble -> mv.visitLdcInsn(instruction.value)
        is Instruction.LoadConstInt -> {
            if (instruction.value < 128 && instruction.value > -128) {
                mv.visitIntInsn(Opcodes.BIPUSH, instruction.value)
            } else {
                mv.visitLdcInsn(instruction.value)
            }
        }
        is Instruction.LoadConstString -> mv.visitLdcInsn(instruction.value)
        is Instruction.LoadVar -> {
            val type = instruction.type(variables, lookup)
            when(type) {
                is Type.JvmType -> mv.visitVarInsn(Opcodes.ALOAD, variables.getId(instruction.name))
                Type.DoubleT -> mv.visitVarInsn(Opcodes.DLOAD, variables.getId(instruction.name))
                Type.IntT, Type.BoolT -> mv.visitVarInsn(Opcodes.ILOAD, variables.getId(instruction.name))
                Type.Nothing -> mv.visitInsn(Opcodes.ACONST_NULL)
                Type.Null -> mv.visitVarInsn(Opcodes.ALOAD, variables.getId(instruction.name))
                is Type.Union -> mv.visitVarInsn(Opcodes.ALOAD, variables.getId(instruction.name))
            }
        }
        is Instruction.StoreVar -> {
            compileInstruction(mv, instruction.value, variables, lookup)
            val type = instruction.value.type(variables, lookup)
            variables.change(instruction.name, type)
            when(type) {
                Type.BoolT, Type.IntT -> mv.visitVarInsn(Opcodes.ISTORE, variables.getId(instruction.name))
                Type.DoubleT -> mv.visitVarInsn(Opcodes.DSTORE, variables.getId(instruction.name))
                is Type.JvmType ->  mv.visitVarInsn(Opcodes.ASTORE, variables.getId(instruction.name))
                Type.Nothing -> mv.visitVarInsn(Opcodes.ASTORE, variables.getId(instruction.name))
                Type.Null -> mv.visitVarInsn(Opcodes.ASTORE, variables.getId(instruction.name))
                is Type.Union -> mv.visitVarInsn(Opcodes.ASTORE, variables.getId(instruction.name))
            }
        }
        is Instruction.Math -> {
            val firstType = instruction.first.type(variables, lookup)
            val secondType = instruction.first.type(variables, lookup)

            if (instruction.op == MathOp.Add && firstType == Type.String) {
                TODO()
            }

            if (firstType.isDouble() || secondType.isDouble()) {
                when {
                    firstType.isDouble() && secondType.isDouble() -> {
                        compileInstruction(mv, instruction.first, variables, lookup)
                        compileInstruction(mv, instruction.second, variables, lookup)
                    }
                    firstType.isDouble() -> {
                        compileInstruction(mv, instruction.first, variables, lookup)
                        compileInstruction(mv, instruction.second, variables, lookup)
                        mv.visitInsn(Opcodes.I2D)
                    }
                    secondType.isDouble() -> {
                        compileInstruction(mv, instruction.first, variables, lookup)
                        mv.visitInsn(Opcodes.I2D)
                        compileInstruction(mv, instruction.second, variables, lookup)
                    }
                }
                mv.visitInsn(when (instruction.op) {
                    MathOp.Add -> Opcodes.DADD
                    MathOp.Sub -> Opcodes.DSUB
                    MathOp.Mul -> Opcodes.DMUL
                    MathOp.Div -> Opcodes.DDIV
                })
            } else {
                //integers
                if (instruction.op == MathOp.Div) {
                    compileInstruction(mv, instruction.first, variables, lookup)
                    mv.visitInsn(Opcodes.I2D)
                    compileInstruction(mv, instruction.second, variables, lookup)
                    mv.visitInsn(Opcodes.I2D)
                    mv.visitInsn(Opcodes.DDIV)
                    return
                }
                compileInstruction(mv, instruction.first, variables, lookup)
                compileInstruction(mv, instruction.second, variables, lookup)
                mv.visitInsn(when (instruction.op) {
                    MathOp.Add -> Opcodes.IADD
                    MathOp.Sub -> Opcodes.ISTORE
                    MathOp.Mul -> Opcodes.IMUL
                    MathOp.Div -> error("Unreachable")
                })

            }
        }
        is Instruction.Comparing -> compileComparison(mv, instruction, variables, lookup)
        is Instruction.ModuleCall -> {
            //load args
            instruction.args.forEach { compileInstruction(mv, it, variables, lookup) }
            val argTypes = instruction.args.map { it.type(variables, lookup) }
            val type = instruction.type(variables, lookup)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                instruction.moduleName.toJvmNotation(),
                instruction.name,
                generateJVMFunctionSignature(argTypes, type),
                false
            )
        }
        //this is essentially the same as a module call BUT its extenral while a module call is internal
        is Instruction.StaticCall -> {
            //load args
            instruction.args.forEach { compileInstruction(mv, it, variables, lookup) }
            val argTypes = instruction.args.map { it.type(variables, lookup) }
            val type = instruction.type(variables, lookup)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                instruction.classModuleName.toJvmNotation(),
                instruction.name,
                generateJVMFunctionSignature(argTypes, type),
                false
            )

        }
        is Instruction.MultiInstructions -> {
            instruction.instructions.forEachIndexed { index, it ->
                compileInstruction(mv, it, variables, lookup)
                //if we leave garbage on the stack and are not the last instruction (which would return)
                if (it.type(variables, lookup) != Type.Nothing && index != instruction.instructions.lastIndex)  {
                    mv.visitInsn(Opcodes.POP)
                }
            }
        }

        is Instruction.StaticPropertyAccess -> {
            val type = instruction.type(variables, lookup)
            mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                instruction.parentName.toJvmNotation(),
                instruction.name,
                type.toJVMDescriptor()
            )
        }

        is Instruction.While -> {
            val top = Label()
            val end = Label()
            mv.visitLabel(top)
            //compile condition
            compileInstruction(mv, instruction.cond, variables, lookup)
            //if the condition is false (equal to 0), jump to end
            mv.visitJumpInsn(Opcodes.IFEQ, end)
            //compile body
            compileInstruction(mv, instruction.body, variables, lookup)
            //mv.visitInsn(Opcodes.POP)
            mv.visitJumpInsn(Opcodes.GOTO, top)
            mv.visitLabel(end)
        }
        Instruction.Null -> mv.visitInsn(Opcodes.ACONST_NULL)
        Instruction.Pop -> mv.visitInsn(Opcodes.POP)
        is Instruction.ConstructorCall -> {
            mv.visitTypeInsn(Opcodes.NEW, instruction.className.toJvmNotation())
            mv.visitInsn(Opcodes.DUP)
            //load args onto stack
            instruction.args.forEach { compileInstruction(mv, it, variables, lookup) }
            val argTypes = instruction.args.map { it.type(variables, lookup) }
            //call constructor
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                instruction.className.toJvmNotation(),
                "<init>",
                generateJVMFunctionSignature(argTypes, Type.Nothing),
                false
            )
        }
    }
}

fun compileDynamicCall(
    mv: MethodVisitor,
    instruction: Instruction.DynamicCall,
    variables: VariableMapping,
    lookup: IRModuleLookup
) {
    val parentType = instruction.parent.type(variables, lookup)
    //load instance onto the stack
    compileInstruction(mv, instruction.parent, variables, lookup)

    when {
        parentType is Type.Union -> {
            val argTypes = instruction.args.map { it.type(variables, lookup) }

            when(val interfaceType = parentType.checkCommonInterfacesForFunction(instruction.name, argTypes, lookup)) {
                is Type.JvmType -> {
                    val returnType = instruction.type(variables, lookup)
                    //mv.visitTypeInsn(Opcodes.CHECKCAST, interfaceType.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
                    instruction.args.forEach { compileInstruction(mv, it, variables, lookup) }
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        interfaceType.toJVMDescriptor().removeSuffix(";").removePrefix("L"),
                        instruction.name,
                        generateJVMFunctionSignature(argTypes, returnType),
                        true
                    )
                }
                else -> {
                    val end = Label()
                    parentType.entries.forEach { type ->
                        val skip = Label()
                        val returnType = lookup.lookUpType(instance = type, funcName = instruction.name, argTypes = argTypes)
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitTypeInsn(Opcodes.INSTANCEOF, type.toJVMDescriptor().removeSuffix(";").removePrefix("L"))
                        mv.visitJumpInsn(Opcodes.IFEQ, skip)
                        mv.visitTypeInsn(Opcodes.CHECKCAST, type.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
                        //perform call
                        //load args onto the stack
                        instruction.args.forEach { compileInstruction(mv, it, variables, lookup) }
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            type.toJVMDescriptor().removeSuffix(";").removePrefix("L"),
                            instruction.name,
                            generateJVMFunctionSignature(argTypes, returnType),
                            false
                        )

                        mv.visitJumpInsn(Opcodes.GOTO, end)
                        mv.visitLabel(skip)
                    }

                    mv.visitLabel(end)
                }
            }
        }
        else -> {
            val returnType = instruction.type(variables, lookup)
            //load args onto the stack
            instruction.args.forEach { compileInstruction(mv, it, variables, lookup) }
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                parentType.toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                instruction.name,
                generateJVMFunctionSignature(instruction.args.map { it.type(variables, lookup) }, returnType),
                false
            )
        }
    }


}


fun compileComparison(
    mv: MethodVisitor,
    instruction: Instruction.Comparing,
    variables: VariableMapping,
    lookup: IRModuleLookup
) {
    val firstType = instruction.first.type(variables, lookup)
    val secondType = instruction.first.type(variables, lookup)
    when(instruction.op) {
        CompareOp.Eq, CompareOp.Neq -> {
            when {
                firstType.isNumType() && secondType.isNumType() ->
                    compileNumberComparison(mv, instruction.first, instruction.second, variables, instruction.op, lookup)

                //when we compare a primitive non-number to a number, it will always be false, so we can evaluate that at compiletime:
                firstType.isUnboxedPrimitive() && !firstType.isNumType() && secondType.isNumType() ->
                    mv.visitInsn(Opcodes.ICONST_0)
                secondType.isUnboxedPrimitive() && !secondType.isNumType() && firstType.isNumType() ->
                    mv.visitInsn(Opcodes.ICONST_0)

                //this should only be called when there are 2 unboxed booleans since every other case was handled already
                firstType.isUnboxedPrimitive() && secondType.isUnboxedPrimitive() -> {
                    //sanity-check
                    assert(firstType == Type.BoolT && secondType == Type.BoolT)
                    compileInstruction(mv, instruction.first, variables, lookup)
                    compileInstruction(mv, instruction.second, variables, lookup)

                    val toElse = Label()
                    val end = Label()
                    mv.visitJumpInsn(Opcodes.IFNE, toElse)
                    mv.visitInsn(if (instruction.op == CompareOp.Eq) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                    mv.visitJumpInsn (Opcodes.GOTO, end)
                    mv.visitLabel(toElse)
                    mv.visitInsn(if (instruction.op == CompareOp.Eq) Opcodes.ICONST_0 else Opcodes.ICONST_1)
                    mv.visitLabel(end)
                }
                //complex objects so we have a .equals method
                else -> {
                    compileInstruction(mv, instruction.first, variables, lookup)
                    //boxes the element if unboxed, and if it's a boxed-type or a complex type it does nothing
                    boxOrIgnore(mv, firstType)
                    compileInstruction(mv, instruction.second, variables, lookup)
                    //boxes the element if unboxed, and if it's a boxed-type or a complex type it does nothing
                    boxOrIgnore(mv, secondType)
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        firstType.asBoxed().toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                        "equals",
                        "(Ljava/lang/Object;)Z",
                        false
                    )
                    if (instruction.op == CompareOp.Neq) {
                        val toFalse = Label()
                        val end = Label()
                        mv.visitJumpInsn(Opcodes.IFNE, toFalse)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitJumpInsn(Opcodes.GOTO, end)
                        mv.visitLabel(toFalse)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitLabel(end)
                    }
                }

            }

        }
        //any operation but EQ and NEQ can only be evaluated for numbers anyway
        else -> compileNumberComparison(mv, instruction.first, instruction.second, variables, instruction.op, lookup)
    }
}

private fun compileNumberComparison(
    mv: MethodVisitor,
    first: Instruction,
    second: Instruction,
    variables: VariableMapping,
    op: CompareOp,
    lookup: IRModuleLookup
) {
    val firstType = first.type(variables, lookup)
    val secondType = first.type(variables, lookup)
    assert(firstType.isNumType() && secondType.isNumType())
    val isIntCmp = firstType.isInt() && secondType.isInt()

    compileInstruction(mv, first, variables, lookup)
    if (firstType.isBoxed()) {
        unbox(mv, firstType)
    }
    if (firstType.isInt() && !isIntCmp) {
        mv.visitInsn(Opcodes.I2D)
    }
    compileInstruction(mv, second, variables, lookup)
    if (secondType.isBoxed()) {
        unbox(mv, secondType)
    }
    if (secondType.isInt() && !isIntCmp) {
        mv.visitInsn(Opcodes.I2D)
    }
    val toFalse = Label()
    val end = Label()
    when(isIntCmp) {
        true -> when (op) {
            CompareOp.Eq -> mv.visitJumpInsn(Opcodes.IF_ICMPNE, toFalse)
            CompareOp.Neq -> mv.visitJumpInsn(Opcodes.IF_ICMPEQ, toFalse)
            CompareOp.Gt -> mv.visitJumpInsn(Opcodes.IF_ICMPLE, toFalse)
            CompareOp.St -> mv.visitJumpInsn(Opcodes.IF_ICMPGE, toFalse)
            CompareOp.EGt -> mv.visitJumpInsn(Opcodes.IF_ICMPLT, toFalse)
            CompareOp.ESt -> mv.visitJumpInsn(Opcodes.IF_ICMPGT, toFalse)
        }
        false -> when (op) {
            CompareOp.Gt -> {
                mv.visitInsn(Opcodes.DCMPL)
                mv.visitJumpInsn(Opcodes.IFLE, toFalse)
            }
            CompareOp.St -> {
                mv.visitInsn(Opcodes.DCMPG)
                mv.visitJumpInsn(Opcodes.IFGE, toFalse)
            }
            CompareOp.EGt -> {
                mv.visitInsn(Opcodes.DCMPL)
                mv.visitJumpInsn(Opcodes.IFLT, toFalse)
            }
            CompareOp.ESt -> {
                mv.visitInsn(Opcodes.DCMPG)
                mv.visitJumpInsn(Opcodes.IFGT, toFalse)
            }
            CompareOp.Eq -> {
                mv.visitInsn(Opcodes.DCMPL)
                mv.visitJumpInsn(Opcodes.IFNE, toFalse)
            }
            CompareOp.Neq -> {
                mv.visitInsn(Opcodes.DCMPL)
                mv.visitJumpInsn(Opcodes.IFEQ, toFalse)
            }
        }
    }
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitJumpInsn (Opcodes.GOTO, end)
    mv.visitLabel(toFalse)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLabel(end)
}

