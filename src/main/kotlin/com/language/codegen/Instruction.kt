package com.language.codegen

import com.language.CompareOp
import com.language.MathOp
import com.language.compilation.*
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import javax.swing.text.LabelView


fun compileInstruction(mv: MethodVisitor, instruction: TypedInstruction, stackMap: StackMap) {
    when(instruction) {
        is TypedInstruction.DynamicCall -> {
            compileDynamicCall(mv, instruction, stackMap)
        }
        is TypedInstruction.Noop -> {}
        is TypedInstruction.DynamicPropertyAccess -> {
            //load instance onto the stack
            compileInstruction(mv, instruction.parent, stackMap)
            when(instruction.parent.type) {
                is Type.Union -> {
                    mv.dynamicDispatch((instruction.parent.type as Type.Union).entries, elseType = instruction.type, stackMap) { type ->
                        mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            type.toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                            instruction.name,
                            instruction.type.toJVMDescriptor()
                        )
                    }
                }
                else -> {
                    mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        instruction.parent.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                        instruction.name,
                        instruction.type.toJVMDescriptor()
                    )
                }
            }
            stackMap.pop() //pop instance
            stackMap.push(instruction.type)
        }
        is TypedInstruction.If -> {
            //eval condition
            compileInstruction(mv, instruction.cond, stackMap)


            val betweenBodyAndElseBody = Label()
            val afterElseBody = Label()
            //if the condition is false goto else
            mv.visitJumpInsn(Opcodes.IFEQ, betweenBodyAndElseBody)
            //the ifeq removes the bool from the stakc
            stackMap.pop()
            //stackMap.generateFrame(mv)
            //body
            compileInstruction(mv, instruction.body, stackMap)
            if (instruction.type != instruction.body.type) {
                boxOrIgnore(mv, instruction.body.type)
            }
            //TODO("Apply changes to variables")

            if(instruction.elseBody == null && instruction.body.type == Type.Nothing) {
                mv.visitLabel(betweenBodyAndElseBody)
                stackMap.generateFrame(mv)
                return
            }

            //pop the result of the other branch for now
            stackMap.pop()


            //skip else body when body was executed
            mv.visitJumpInsn(Opcodes.GOTO, afterElseBody)

            stackMap.generateFrame(mv)
            //here is the between body and else body label
            mv.visitLabel(betweenBodyAndElseBody)
            //else body
            compileInstruction(mv, instruction.elseBody ?: TypedInstruction.Null, stackMap)
            if (instruction.type != instruction.elseBody?.type) {
                boxOrIgnore(mv, instruction.elseBody?.type ?: Type.Null)
            }
            //TODO("Apply changes to variables")
            //label after else body
            mv.visitLabel(afterElseBody)

            stackMap.pop()
            stackMap.push(instruction.type)
            stackMap.generateFrame(mv)

        }
        is TypedInstruction.LoadConstBoolean -> {
            when(instruction.value) {
                true -> mv.visitInsn(Opcodes.ICONST_1)
                false -> mv.visitInsn(Opcodes.ICONST_0)
            }.also { stackMap.push(Type.BoolT) }
        }
        is TypedInstruction.LoadConstDouble -> {
            mv.visitLdcInsn(instruction.value).also { stackMap.push(Type.DoubleT) }
        }
        is TypedInstruction.LoadConstInt -> {
            if (instruction.value < 128 && instruction.value > -128) {
                mv.visitIntInsn(Opcodes.BIPUSH, instruction.value)
            } else {
                mv.visitLdcInsn(instruction.value)
            }
            stackMap.push(Type.IntT)
        }
        is TypedInstruction.LoadConstString -> {
            mv.visitLdcInsn(instruction.value).also { stackMap.push(Type.String) }
        }
        is TypedInstruction.LoadVar -> {
            when(instruction.type) {
                is Type.JvmType -> mv.visitVarInsn(Opcodes.ALOAD, instruction.id)
                Type.DoubleT -> mv.visitVarInsn(Opcodes.DLOAD, instruction.id)
                Type.IntT, Type.BoolT -> mv.visitVarInsn(Opcodes.ILOAD, instruction.id)
                Type.Nothing -> mv.visitInsn(Opcodes.ACONST_NULL)
                Type.Null -> mv.visitVarInsn(Opcodes.ALOAD, instruction.id)
                is Type.Union -> mv.visitVarInsn(Opcodes.ALOAD, instruction.id)
            }
            stackMap.push(instruction.type)
        }
        is TypedInstruction.StoreVar -> {
            compileInstruction(mv, instruction.value, stackMap)
            when(instruction.value.type) {
                Type.BoolT, Type.IntT -> mv.visitVarInsn(Opcodes.ISTORE, instruction.id)
                Type.DoubleT -> mv.visitVarInsn(Opcodes.DSTORE, instruction.id)
                is Type.JvmType ->  mv.visitVarInsn(Opcodes.ASTORE, instruction.id)
                Type.Nothing -> mv.visitVarInsn(Opcodes.ASTORE, instruction.id)
                Type.Null -> mv.visitVarInsn(Opcodes.ASTORE, instruction.id)
                is Type.Union -> mv.visitVarInsn(Opcodes.ASTORE, instruction.id)
            }
            stackMap.changeVar(instruction.id, instruction.value.type)
            //storing removes an item from the stack
            stackMap.pop()
        }
        is TypedInstruction.Math -> {
            val secondType = instruction.second.type
            val firstType = instruction.first.type

            if (instruction.op == MathOp.Add && instruction.first.type == Type.String) {
                compileInstruction(mv, instruction.first, stackMap)
                compileInstruction(mv, instruction.second, stackMap)
                if (secondType != Type.String) {
                    val tp = when(secondType) {
                        Type.IntT -> "I"
                        Type.DoubleT -> "D"
                        Type.BoolT -> "Z"
                        else -> "Ljava/lang/Object;"
                    }
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "java/lang/String",
                        "valueOf",
                        "($tp)Ljava/lang/String;",
                        false
                    )
                }
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false
                )
                stackMap.pop(2)
                stackMap.push(Type.String)
                return
            }

            if (firstType.isDouble() || secondType.isDouble()) {
                when {
                    firstType.isDouble() && secondType.isDouble() -> {
                        compileInstruction(mv, instruction.first, stackMap)
                        compileInstruction(mv, instruction.second, stackMap)
                    }
                    firstType.isDouble() -> {
                        compileInstruction(mv, instruction.first, stackMap)
                        compileInstruction(mv, instruction.second, stackMap)
                        mv.visitInsn(Opcodes.I2D)
                    }
                    secondType.isDouble() -> {
                        compileInstruction(mv, instruction.first, stackMap)
                        mv.visitInsn(Opcodes.I2D)
                        compileInstruction(mv, instruction.second, stackMap)
                    }
                }
                mv.visitInsn(when (instruction.op) {
                    MathOp.Add -> Opcodes.DADD
                    MathOp.Sub -> Opcodes.DSUB
                    MathOp.Mul -> Opcodes.DMUL
                    MathOp.Div -> Opcodes.DDIV
                })
                stackMap.pop(2)
                stackMap.push(Type.DoubleT)
            } else {
                //integers
                if (instruction.op == MathOp.Div) {
                    compileInstruction(mv, instruction.first, stackMap)
                    mv.visitInsn(Opcodes.I2D)
                    compileInstruction(mv, instruction.second, stackMap)
                    mv.visitInsn(Opcodes.I2D)
                    mv.visitInsn(Opcodes.DDIV)
                    return
                }
                compileInstruction(mv, instruction.first, stackMap)
                compileInstruction(mv, instruction.second, stackMap)
                mv.visitInsn(when (instruction.op) {
                    MathOp.Add -> Opcodes.IADD
                    MathOp.Sub -> Opcodes.ISTORE
                    MathOp.Mul -> Opcodes.IMUL
                    MathOp.Div -> error("Unreachable")
                })
                stackMap.pop(2)
                stackMap.push(Type.IntT)
            }
        }
        is TypedInstruction.Comparing -> {
            compileComparison(mv, instruction, stackMap)
        }
        is TypedInstruction.StaticCall -> {
            //load args
            instruction.args.forEach { compileInstruction(mv, it, stackMap) }
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                instruction.parentName.toJvmNotation(),
                instruction.name,
                instruction.candidate.toJvmDescriptor(),
                false
            )
            stackMap.pop(instruction.candidate.oxideArgs.size)
            stackMap.push(instruction.candidate.oxideReturnType)
        }
        is TypedInstruction.ModuleCall -> {
            //load args
            instruction.args.forEach { compileInstruction(mv, it, stackMap) }
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                instruction.parentName.toJvmNotation(),
                jvmName(instruction.name, instruction.candidate.oxideArgs),
                instruction.candidate.toJvmDescriptor(),
                false
            )
            stackMap.pop(instruction.candidate.oxideArgs.size)
            stackMap.push(instruction.candidate.oxideReturnType)
        }
        is TypedInstruction.MultiInstructions -> {
            stackMap.pushVarFrame(instruction.varFrame)
            //stackMap.generateFrame(mv)
            instruction.instructions.forEachIndexed { index, it ->
                compileInstruction(mv, it, stackMap)
                //if we leave garbage on the stack and are not the last instruction (which would return)
                if (it.type != Type.Nothing && index != instruction.instructions.lastIndex)  {
                    mv.visitInsn(Opcodes.POP)
                    stackMap.pop()
                }
            }
            stackMap.popVarFrame()
        }

        is TypedInstruction.StaticPropertyAccess -> {
            mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                instruction.parentName.toJvmNotation(),
                instruction.name,
                instruction.type.toJVMDescriptor()
            )
            stackMap.push(instruction.type)
        }

        is TypedInstruction.While -> {
            val top = Label()
            val end = Label()
            mv.visitLabel(top)
            //compile condition
            stackMap.generateFrame(mv)
            compileInstruction(mv, instruction.cond, stackMap)

            //if the condition is false (equal to 0), jump to end
            mv.visitJumpInsn(Opcodes.IFEQ, end)
            stackMap.pop()
            //compile body
            compileInstruction(mv, instruction.body, stackMap)

            if (instruction.body.type != Type.Nothing) {
                mv.visitInsn(Opcodes.POP)
                stackMap.pop()
            }
            when(instruction.body) {
                is TypedInstruction.MultiInstructions ->stackMap.generateFrame(mv, instruction.body.varFrame)
                else -> stackMap.generateFrame(mv)
            }
            mv.visitJumpInsn(Opcodes.GOTO, top)
            mv.visitLabel(end)
            stackMap.generateFrame(mv)
        }
        TypedInstruction.Null -> {
            mv.visitInsn(Opcodes.ACONST_NULL)
            stackMap.push(Type.Null)
        }
        TypedInstruction.Pop -> {
            mv.visitInsn(Opcodes.POP)
            stackMap.pop()
        }
        is TypedInstruction.ConstructorCall -> {
            mv.visitTypeInsn(Opcodes.NEW, instruction.className.toJvmNotation())
            mv.visitInsn(Opcodes.DUP)
            //load args onto stack
            instruction.args.forEach { compileInstruction(mv, it, stackMap) }
            //call constructor
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                instruction.className.toJvmNotation(),
                "<init>",
                instruction.candidate.toJvmDescriptor(),
                false
            )
            stackMap.pop(instruction.args.size)
            stackMap.push(instruction.candidate.oxideReturnType)
        }

        is TypedInstruction.Dup -> {
            mv.visitInsn(Opcodes.DUP)
            stackMap.dup()
        }
        is TypedInstruction.Match -> compileMatch(mv, instruction, stackMap)
    }
}

fun compileMatch(
    mv: MethodVisitor,
    instruction: TypedInstruction.Match,
    stackMap: StackMap
) {
    //load the matchable parent onto the stack
    compileInstruction(mv, instruction.parent, stackMap)
    val end = Label()
    for ((pattern, body) in instruction.patterns) {
        mv.visitInsn(Opcodes.DUP)
        stackMap.dup()
        val patternFail = Label()
        compilePattern(mv, pattern, stackMap, patternFail)
        //mv.visitInsn(Opcodes.POP)
        //stackMap.pop()
        compileInstruction(mv, body, stackMap)
        mv.visitJumpInsn(Opcodes.GOTO, end)
        mv.visitLabel(patternFail)
    }
    mv.visitLabel(end)
}

fun compilePattern(
    mv: MethodVisitor,
    pattern: TypedIRPattern,
    stackMap: StackMap,
    patternFail: Label
) {
    when(pattern) {
        is TypedIRPattern.Binding -> {
            //bind the binding (move it into its variable slot)
            compileInstruction(mv, TypedInstruction.StoreVar(pattern.id, pattern.origin), stackMap)
        }
        is TypedIRPattern.Condition -> {
            compilePattern(mv, pattern.parent, stackMap, patternFail)
            compileInstruction(mv, pattern.condition, stackMap)
            //if condition equal to is 0 (false) go to the fail stage
            mv.visitJumpInsn(Opcodes.IFEQ, patternFail)
            stackMap.pop()
        }
        is TypedIRPattern.Destructuring -> {
            val skitFail = Label()
            compileInstruction(mv, pattern.origin, stackMap)
            mv.visitInsn(Opcodes.DUP)
            stackMap.dup()
            mv.visitTypeInsn(Opcodes.INSTANCEOF, pattern.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
            stackMap.pop()
            stackMap.push(Type.BoolT)
            mv.visitJumpInsn(Opcodes.IFNE, skitFail)
            stackMap.pop()
            //
            mv.visitInsn(Opcodes.POP)
            stackMap.pop()
            //instance of check failed so go to the fail stage
            mv.visitJumpInsn(Opcodes.GOTO, patternFail)
            mv.visitLabel(skitFail)

            //compile all the desturcturing patterns
            pattern.patterns.forEach {
                mv.visitInsn(Opcodes.DUP)
                stackMap.dup()
                compilePattern(mv, it, stackMap, patternFail)
            }
            mv.visitInsn(Opcodes.POP)
            stackMap.pop()
        }
    }
}

fun compileDynamicCall(
    mv: MethodVisitor,
    instruction: TypedInstruction.DynamicCall,
    stackMap: StackMap
) {
    val parentType = instruction.parent.type.asBoxed()
    //load instance onto the stack
    compileInstruction(mv, instruction.parent, stackMap)
    boxOrIgnore(mv, instruction.parent.type)
    when {
        parentType is Type.Union -> {
            when (val commonType = instruction.commonInterface) {
                is Type.JvmType -> {
                    mv.loadAndBox(instruction.candidate, instruction.args, stackMap)
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        commonType.toJVMDescriptor().removeSuffix(";").removePrefix("L"),
                        instruction.name,
                        instruction.candidate.toJvmDescriptor(),
                        true
                    )

                    if (instruction.candidate.hasGenericReturnType) {
                        mv.visitTypeInsn(Opcodes.CHECKCAST, instruction.candidate.oxideReturnType.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
                    }
                }

                else -> {
                    mv.dynamicDispatch(parentType.entries, elseType = instruction.type, stackMap) { type ->
                        mv.loadAndBox(instruction.candidate, instruction.args, stackMap)

                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            type.toJVMDescriptor().removeSuffix(";").removePrefix("L"),
                            instruction.name,
                            instruction.candidate.toJvmDescriptor(),
                            false
                        )
                        if (instruction.candidate.hasGenericReturnType) {
                            mv.visitTypeInsn(Opcodes.CHECKCAST, instruction.candidate.oxideReturnType.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
                        }
                    }
                }
            }
        }

        else -> {
            //load args onto the stack
            mv.loadAndBox(instruction.candidate, instruction.args, stackMap)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                parentType.toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                instruction.name,
                instruction.candidate.toJvmDescriptor(),
                false
            )
            if (instruction.candidate.hasGenericReturnType) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, instruction.candidate.oxideReturnType.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
            }
        }


    }

    stackMap.pop(instruction.candidate.oxideArgs.size+1) //args + instance
    stackMap.push(instruction.candidate.oxideReturnType)
}


fun MethodVisitor.loadAndBox(candidate: FunctionCandidate, args: Iterable<TypedInstruction>, stackMap: StackMap) {
    for ((ins, type) in args.zip(candidate.jvmArgs)) {
        compileInstruction(this, ins, stackMap)
        if (type != ins.type) {
            if (ins.type.isInt() && type.isDouble()) {
                visitInsn(Opcodes.I2D)
            }
            boxOrIgnore(this, ins.type)
        }
    }
}



inline fun MethodVisitor.dynamicDispatch(types: Iterable<Type>, elseType: Type, stackMap: StackMap, task: (type: Type) -> Unit) {
    val end = Label()

    types.forEach { type ->
        val skip = Label()
        visitInsn(Opcodes.DUP)
        visitTypeInsn(Opcodes.INSTANCEOF, type.toJVMDescriptor().removeSuffix(";").removePrefix("L"))
        visitJumpInsn(Opcodes.IFEQ, skip)
        stackMap.generateFrame(this)

        visitTypeInsn(Opcodes.CHECKCAST, type.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
        task(type)

        visitJumpInsn(Opcodes.GOTO, end)

        visitLabel(skip)
        stackMap.generateFrame(this)

    }
    //introduce an else-case that literally never happens since we know its exhaustive just to keep the jvm happy
    visitInsn(Opcodes.POP)
    stackMap.pop()
    when(elseType) {
        Type.BoolT, Type.IntT -> visitInsn(Opcodes.ICONST_0)
        Type.DoubleT -> visitLdcInsn(0.0)
        is Type.BasicJvmType, Type.Null, is Type.Union -> visitInsn(Opcodes.ACONST_NULL)
        Type.Nothing -> {}
    }

    stackMap.push(elseType)
    visitLabel(end)
    stackMap.generateFrame(this)

}


fun compileComparison(
    mv: MethodVisitor,
    instruction: TypedInstruction.Comparing,
    stackMap: StackMap
) {
    val firstType = instruction.first.type
    val secondType = instruction.first.type
    when(instruction.op) {
        CompareOp.Eq, CompareOp.Neq -> {
            when {
                firstType.isNumType() && secondType.isNumType() ->
                    compileNumberComparison(mv, instruction.first, instruction.second, instruction.op, stackMap)

                //when we compare a primitive non-number to a number, it will always be false, so we can evaluate that at compiletime:
                firstType.isUnboxedPrimitive() && !firstType.isNumType() && secondType.isNumType() ->

                    mv.visitInsn(Opcodes.ICONST_0).also { stackMap.push(Type.BoolT) }
                secondType.isUnboxedPrimitive() && !secondType.isNumType() && firstType.isNumType() ->
                    mv.visitInsn(Opcodes.ICONST_0).also { stackMap.push(Type.BoolT) }

                //this should only be called when there are 2 unboxed booleans since every other case was handled already
                firstType.isUnboxedPrimitive() && secondType.isUnboxedPrimitive() -> {
                    //sanity-check
                    assert(firstType == Type.BoolT && secondType == Type.BoolT)
                    compileInstruction(mv, instruction.first, stackMap)
                    compileInstruction(mv, instruction.second, stackMap)
                    stackMap.pop(2)
                    val toElse = Label()
                    val end = Label()
                    mv.visitJumpInsn(Opcodes.IFNE, toElse)
                    mv.visitInsn(if (instruction.op == CompareOp.Eq) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                    mv.visitJumpInsn (Opcodes.GOTO, end)
                    mv.visitLabel(toElse)
                    mv.visitInsn(if (instruction.op == CompareOp.Eq) Opcodes.ICONST_0 else Opcodes.ICONST_1)
                    mv.visitLabel(end)
                    stackMap.push(Type.BoolT)
                }
                //complex objects so we have a .equals method
                else -> {
                    compileInstruction(mv, instruction.first, stackMap)
                    //boxes the element if unboxed, and if it's a boxed-type or a complex type it does nothing
                    boxOrIgnore(mv, firstType)
                    compileInstruction(mv, instruction.second, stackMap)
                    //boxes the element if unboxed, and if it's a boxed-type or a complex type it does nothing
                    boxOrIgnore(mv, secondType)
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        firstType.asBoxed().toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                        "equals",
                        "(Ljava/lang/Object;)Z",
                        false
                    )
                    stackMap.pop(2)
                    stackMap.push(Type.BoolT)
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
        else -> compileNumberComparison(mv, instruction.first, instruction.second, instruction.op, stackMap)
    }
}

private fun compileNumberComparison(
    mv: MethodVisitor,
    first: TypedInstruction,
    second: TypedInstruction,
    op: CompareOp,
    stackMap: StackMap
) {
    val firstType = first.type
    val secondType = second.type
    assert(firstType.isNumType() && secondType.isNumType())
    val isIntCmp = firstType.isInt() && secondType.isInt()

    compileInstruction(mv, first, stackMap)
    if (firstType.isBoxed()) {
        stackMap.pop()
        stackMap.push(secondType.asUnboxed())
        unbox(mv, firstType)
    }
    if (firstType.isInt() && !isIntCmp) {
        mv.visitInsn(Opcodes.I2D)
    }
    compileInstruction(mv, second, stackMap)
    if (secondType.isBoxed()) {
        stackMap.pop()
        stackMap.push(secondType.asUnboxed())
        unbox(mv, secondType)
    }
    if (secondType.isInt() && !isIntCmp) {
        mv.visitInsn(Opcodes.I2D)
    }
    val toFalse = Label()
    val end = Label()
    //mv.visitFrame(Opcodes.F_FULL, 2, arrayOf(Opcodes.INTEGER, Opcodes.INTEGER), 2, arrayOf(Opcodes.INTEGER, Opcodes.INTEGER))
    //stackMap.generateFrame(mv)
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
    stackMap.pop(2)
    //stackMap.generateFrame(mv)
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitJumpInsn (Opcodes.GOTO, end)
    mv.visitLabel(toFalse)
    stackMap.generateFrame(mv)
    //mv.visitFrame(Opcodes.F_APPEND, 0, emptyArray(), 1, arrayOf(Opcodes.INTEGER))
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLabel(end)
    stackMap.push(Type.BoolT)
    stackMap.generateFrame(mv)

}

fun Type.asUnboxed() = when (this) {
    Type.BoolT, Type.Bool-> Type.BoolT
    Type.DoubleT, Type.Double -> Type.DoubleT
    Type.IntT, Type.Int -> Type.IntT
    else -> error("Cannot unbox $this")
}
