package com.language.codegen

import com.language.CompareOp
import com.language.MathOp
import com.language.Pattern
import com.language.compilation.*
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes


fun compileInstruction(mv: MethodVisitor, instruction: TypedInstruction, stackMap: StackMap) {
    when(instruction) {
        is TypedInstruction.DynamicCall -> compileDynamicCall(mv, instruction, stackMap)
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
        is TypedInstruction.DynamicPropertyAssignment -> {
            //load instance onto the stack
            compileInstruction(mv, instruction.parent, stackMap)
            compileInstruction(mv, instruction.value, stackMap)

            mv.visitFieldInsn(
                Opcodes.PUTFIELD,
                instruction.parent.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                instruction.name,
                instruction.value.type.toJVMDescriptor()
            )


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
            }.also { stackMap.push(Type.BoolUnknown) }
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
                is Type.JvmType, is Type.Array -> mv.visitVarInsn(Opcodes.ALOAD, instruction.id)
                Type.DoubleT -> mv.visitVarInsn(Opcodes.DLOAD, instruction.id)
                Type.IntT, is Type.BoolT -> mv.visitVarInsn(Opcodes.ILOAD, instruction.id)
                Type.Nothing -> mv.visitInsn(Opcodes.ACONST_NULL)
                Type.Null -> mv.visitVarInsn(Opcodes.ALOAD, instruction.id)
                is Type.Union -> mv.visitVarInsn(Opcodes.ALOAD, instruction.id)
                Type.Never -> error("Cannot load variable of type Never")
            }
            stackMap.push(instruction.type)
        }
        is TypedInstruction.StoreVar -> {
            compileInstruction(mv, instruction.value, stackMap)
            when(instruction.value.type) {
                is Type.BoolT, Type.IntT -> mv.visitVarInsn(Opcodes.ISTORE, instruction.id)
                Type.DoubleT -> mv.visitVarInsn(Opcodes.DSTORE, instruction.id)
                is Type.JvmType ->  mv.visitVarInsn(Opcodes.ASTORE, instruction.id)
                Type.Nothing -> mv.visitVarInsn(Opcodes.ASTORE, instruction.id)
                Type.Null, is Type.Array -> mv.visitVarInsn(Opcodes.ASTORE, instruction.id)
                is Type.Union -> mv.visitVarInsn(Opcodes.ASTORE, instruction.id)
                Type.Never -> error("Cannot store variable of type never")
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
                        is Type.BoolT -> "Z"
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
            mv.loadAndBox(instruction.candidate, instruction.args, stackMap)
            instruction.candidate.generateCall(mv)
            stackMap.pop(instruction.candidate.oxideArgs.size)
            stackMap.push(instruction.candidate.oxideReturnType)
        }
        is TypedInstruction.ModuleCall -> {
            //load args
            mv.loadAndBox(instruction.candidate, instruction.args, stackMap)
            instruction.candidate.generateCall(mv)
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
            mv.loadAndBox(instruction.candidate, instruction.args, stackMap)
            //call constructor
            instruction.candidate.generateCall(mv)
            stackMap.pop(instruction.args.size)
            stackMap.push(instruction.candidate.oxideReturnType)
        }

        is TypedInstruction.Dup -> {
            mv.visitInsn(Opcodes.DUP)
            stackMap.dup()
        }
        is TypedInstruction.Match -> compileMatch(mv, instruction, stackMap)
        is TypedInstruction.LoadList -> {

            mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
            stackMap.push(instruction.type)
            mv.visitInsn(Opcodes.DUP)
            stackMap.dup()
            if (instruction.isConstList) {
                compileInstruction(mv, TypedInstruction.LoadConstInt(instruction.items.size), stackMap)
            }
            if (instruction.isConstList) {
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false)
                stackMap.pop(2)
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
                stackMap.pop()
            }
            for(item in instruction.items) {
                mv.visitInsn(Opcodes.DUP)
                stackMap.dup()
                compileInstruction(mv, item.instruction, stackMap)
                boxOrIgnore(mv, item.type)
                when(item) {
                    is TypedConstructingArgument.Collected -> {
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "addAll", "(Ljava/util/Collection;)Z", false)
                    }
                    is TypedConstructingArgument.Normal -> {
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false)
                    }
                }
                mv.visitInsn(Opcodes.POP)
                stackMap.pop(2)

            }
        }
        is TypedInstruction.LoadConstArray -> {
            compileInstruction(mv, TypedInstruction.LoadConstInt(instruction.items.size), stackMap)
            when(instruction.arrayType) {
                ArrayType.Int -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
                ArrayType.Double -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE)
                ArrayType.Bool -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN)
                ArrayType.Object -> mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
            }
            stackMap.pop()
            stackMap.push(instruction.type)

            instruction.items.forEachIndexed { index, item ->
                mv.visitInsn(Opcodes.DUP)
                stackMap.dup()
                compileInstruction(mv, TypedInstruction.LoadConstInt(index), stackMap)
                compileInstruction(mv, item, stackMap)
                if (instruction.arrayType == ArrayType.Object) {
                    boxOrIgnore(mv, item.type)
                }
                when(instruction.arrayType) {
                    ArrayType.Int -> mv.visitInsn(Opcodes.IASTORE)
                    ArrayType.Double -> mv.visitInsn(Opcodes.DASTORE)
                    ArrayType.Bool -> mv.visitInsn(Opcodes.IASTORE)
                    ArrayType.Object -> mv.visitInsn(Opcodes.AASTORE)
                }
                stackMap.pop()
                stackMap.pop()
                stackMap.pop()
            }

        }
        is TypedInstruction.LoadArray -> {
            //load the number of items on the stack
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitVarInsn(Opcodes.ISTORE, instruction.tempIndexVarId)
            for (item in instruction.items) {
                compileInstruction(mv, item.instruction, stackMap)
                if (instruction.arrayType == ArrayType.Object) {
                    boxOrIgnore(mv, item.instruction.type)
                }
                when(item) {
                    is TypedConstructingArgument.Collected -> {
                        //make `stackmap` contain the item, itemLength
                        stackMap.push(Type.IntT)

                        //Now lets make the actual bytecode also contain item, itemLength
                        //item
                        mv.visitInsn(Opcodes.DUP)
                        //item item
                        when(item.type) {
                            is Type.Array -> {
                                mv.visitInsn(Opcodes.ARRAYLENGTH)
                                //item itemLength
                                mv.visitInsn(Opcodes.DUP)
                                //item itemLength, itemLength

                                //This will then be added and results in
                                //item itemLength, newDLength
                            }
                            else -> TODO()
                        }
                    }
                    is TypedConstructingArgument.Normal -> {
                        mv.visitInsn(Opcodes.ICONST_1)
                    }
                }
                mv.visitVarInsn(Opcodes.ILOAD, instruction.tempIndexVarId)
                mv.visitInsn(Opcodes.IADD)
                mv.visitVarInsn(Opcodes.ISTORE, instruction.tempIndexVarId)
            }

            for (item in instruction.items) {
                stackMap.pop()
                when(item) {
                    is TypedConstructingArgument.Collected -> stackMap.pop()
                    is TypedConstructingArgument.Normal -> {}
                }
            }
            mv.visitVarInsn(Opcodes.ILOAD, instruction.tempIndexVarId)
            mv.visitInsn(Opcodes.DUP)
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitInsn(Opcodes.ISUB)
            mv.visitInsn(Opcodes.SWAP)
            when(instruction.arrayType) {
                ArrayType.Int -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
                ArrayType.Double -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE)
                ArrayType.Bool -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN)
                ArrayType.Object -> mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
            }
            mv.visitInsn(Opcodes.DUP)
            mv.visitVarInsn(Opcodes.ASTORE, instruction.tempArrayVarId)
            //NOTE: The following comments represent the items on the stack.
            //The further right an item is, the closer it is to the top of the stack

            //item int instance
            instruction.items.asReversed().forEachIndexed { i, item ->
                when(item) {
                    is TypedConstructingArgument.Normal -> {
                        mv.visitInsn(Opcodes.DUP_X2)
                        mv.visitInsn(Opcodes.DUP_X2)
                        //instance, instance, item, int, instance

                        mv.visitInsn(Opcodes.POP)
                        //instance, instance, item, int
                        mv.visitInsn(Opcodes.DUP_X2)
                        //instance, int, instance, item, int
                        mv.visitInsn(Opcodes.SWAP)
                        //instance, int, instance, int, item
                        when(instruction.arrayType) {
                            ArrayType.Int -> mv.visitInsn(Opcodes.IASTORE)
                            ArrayType.Double -> mv.visitInsn(Opcodes.DASTORE)
                            ArrayType.Bool -> mv.visitInsn(Opcodes.IASTORE)
                            ArrayType.Object -> mv.visitInsn(Opcodes.AASTORE)
                        }
                        //instance, int
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitInsn(Opcodes.ISUB)

                        if (i != instruction.items.lastIndex)
                            mv.visitInsn(Opcodes.SWAP)
                        //nextItem, int, instance
                        else
                            mv.visitInsn(Opcodes.POP)
                        //instance
                    }
                    is TypedConstructingArgument.Collected -> {
                        //srcArray, srcLength, index, instance

                        mv.visitInsn(Opcodes.POP)
                        //srcArray, srcLength, index
                        mv.visitInsn(Opcodes.SWAP)
                        mv.visitInsn(Opcodes.DUP_X1)
                        mv.visitInsn(Opcodes.ISUB)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitInsn(Opcodes.IADD)
                        //srcArray, srcLegnth, newIndex
                        mv.visitInsn(Opcodes.DUP_X2)
                        //newIndex, srcArray, srcLength, newIndex
                        mv.visitInsn(Opcodes.SWAP)
                        //newIndex, srcArray, newIndex, srcLength
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitInsn(Opcodes.DUP_X2)
                        mv.visitInsn(Opcodes.POP)
                        //newIndex, srcArray, 0, newIndex, srcLength
                        mv.visitVarInsn(Opcodes.ALOAD, instruction.tempArrayVarId)
                        mv.visitInsn(Opcodes.DUP_X2)
                        mv.visitInsn(Opcodes.POP)
                        //newIndex, srcArray, 0, instance, newIndex, srcLength
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false)
                        //newIndex
                        if (i == instruction.items.lastIndex)
                            mv.visitInsn(Opcodes.POP)
                        mv.visitVarInsn(Opcodes.ALOAD, instruction.tempArrayVarId)

                    }
                }

            }
            stackMap.push(Type.Array(instruction.itemType))
        }

        is TypedInstruction.ForLoop -> {
            compileInstruction(mv, instruction.parent, stackMap)
            val start = Label()
            val end = Label()
            mv.visitLabel(start)
            stackMap.generateFrame(mv)
            mv.visitInsn(Opcodes.DUP)
            instruction.hasNextCall.generateCall(mv)
            mv.visitJumpInsn(Opcodes.IFEQ, end)
            mv.visitInsn(Opcodes.DUP)
            instruction.nextCall.generateCall(mv)
            val storeInstruction = when(instruction.nextCall.oxideReturnType) {
                is Type.IntT -> Opcodes.ISTORE
                is Type.BoolT -> Opcodes.ISTORE
                is Type.DoubleT -> Opcodes.DSTORE
                else -> Opcodes.ASTORE
            }
            mv.visitVarInsn(storeInstruction, instruction.itemId)
            compileInstruction(mv, instruction.body, stackMap)
            when (instruction.body.type) {
                Type.Nothing -> {}
                else -> {
                    stackMap.pop()
                    mv.visitInsn(Opcodes.POP)
                }
            }
            mv.visitJumpInsn(Opcodes.GOTO, start)
            mv.visitLabel(end)
            stackMap.generateFrame(mv)
            mv.visitInsn(Opcodes.POP)
            stackMap.pop()
        }
        is TypedInstruction.Try -> {
            compileInstruction(mv, instruction.parent, stackMap)
            for (signature in instruction.errorTypes) {
                mv.visitInsn(Opcodes.DUP)
                mv.visitTypeInsn(Opcodes.INSTANCEOF, signature.toJvmNotation())
                val skip = Label()
                mv.visitJumpInsn(Opcodes.IFEQ, skip)
                mv.visitInsn(Opcodes.ARETURN)
                mv.visitLabel(skip)
                stackMap.generateFrame(mv)
            }
        }
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
    var idx = 0
    for ((pattern, body, varFrame) in instruction.patterns) {
        stackMap.generateFrame(mv)
        stackMap.pushVarFrame(varFrame, cloning = false)
        if (idx != instruction.patterns.lastIndex) {
            mv.visitInsn(Opcodes.DUP)
            stackMap.dup()
            if (pattern is TypedIRPattern.Binding) mv.visitInsn(Opcodes.DUP)
        }

        val patternFail = Label()
        compilePattern(mv, pattern, stackMap, patternFail)
        //mv.visitInsn(Opcodes.POP)
        //stackMap.pop()
        compileInstruction(mv, body, stackMap)
        if (instruction.type != body.type) {
            boxOrIgnore(mv, body.type)
            unboxOrIgnore(mv, body.type)
        }
        if (idx != instruction.patterns.lastIndex) {
            if (body.type != Type.Nothing) {
                mv.visitInsn(Opcodes.SWAP)
            }
            mv.visitInsn(Opcodes.POP)
        }
        mv.visitJumpInsn(Opcodes.GOTO, end)

        if (body.type != Type.Nothing) {
            //we pop the value produced by the body in this loop since we dont need it rn
            //on the stackmap however, we keep it in the actual bytecode
            stackMap.pop()
        }

        mv.visitLabel(patternFail)
        stackMap.popVarFrame()
        idx++
    }
    mv.visitLabel(end)
    if (instruction.type != Type.Nothing) {
        stackMap.push(instruction.type)
    }
    stackMap.generateFrame(mv)
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
            val skip = Label()
            mv.visitJumpInsn(Opcodes.IFNE, skip)
            //mv.visitInsn(Opcodes.POP)
            mv.visitJumpInsn(Opcodes.GOTO, patternFail)
            mv.visitLabel(skip)
            stackMap.pop()
            stackMap.generateFrame(mv)
        }
        is TypedIRPattern.Destructuring -> compilePatternDestructuring(mv, pattern, stackMap, patternFail)
    }
}


fun compilePatternDestructuring(
    mv: MethodVisitor,
    pattern: TypedIRPattern.Destructuring,
    stackMap: StackMap,
    patternFail: Label
) {
    if (pattern.isLast) {
        when(pattern.castStoreId) {
            null -> {
                mv.visitInsn(Opcodes.POP)
                stackMap.pop()
            }
            else -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, pattern.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
                compileInstruction(mv, TypedInstruction.StoreVar(pattern.castStoreId, TypedInstruction.Noop(pattern.type)), stackMap)
            }
        }
        return
    }

    val success = Label()
    compileInstruction(mv, pattern.origin, stackMap)
    if (pattern.patterns.isNotEmpty()) {
        mv.visitInsn(Opcodes.DUP)
        stackMap.dup()
    }

    mv.visitTypeInsn(Opcodes.INSTANCEOF, pattern.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
    stackMap.pop()
    stackMap.push(Type.BoolUnknown)
    mv.visitJumpInsn(Opcodes.IFNE, success)
    stackMap.pop()

    //IF FAIL
    if (pattern.patterns.isNotEmpty()) {
        mv.visitInsn(Opcodes.POP)
    }

    //instance of check failed so go to the fail stage
    mv.visitJumpInsn(Opcodes.GOTO, patternFail)
    //END IF FAIL

    //IF SUCCESS
    mv.visitLabel(success)
    stackMap.generateFrame(mv)
    mv.visitTypeInsn(Opcodes.CHECKCAST, pattern.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"))

    if (pattern.castStoreId != null) {
        compileInstruction(mv, TypedInstruction.StoreVar(pattern.castStoreId, TypedInstruction.Dup(pattern.type)), stackMap)
    }

    //compile all the desturcturing patterns
    pattern.patterns.forEach {
        mv.visitInsn(Opcodes.DUP)
        stackMap.dup()
        compilePattern(mv, it, stackMap, patternFail)
    }
    if (pattern.patterns.isNotEmpty()) {
        mv.visitInsn(Opcodes.POP)
        stackMap.pop()
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
    //boxOrIgnore(mv, instruction.parent.type)
    when {
        parentType is Type.Union -> {
            when (val commonType = instruction.commonInterface) {
                is Type.JvmType -> {
                    mv.loadAndBox(instruction.candidate, instruction.args, stackMap)
                    instruction.candidate.generateCall(mv)

                    if (instruction.candidate.hasGenericReturnType) {
                        mv.visitTypeInsn(Opcodes.CHECKCAST, instruction.candidate.oxideReturnType.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
                    }
                }

                else -> {
                    if (instruction.candidate.requireDispatch) {
                        mv.dynamicDispatch(parentType.entries, elseType = instruction.type, stackMap) { type ->
                            mv.loadAndBox(instruction.candidate, instruction.args, stackMap)

                            instruction.candidate.generateCall(mv)
                            if (instruction.candidate.hasGenericReturnType) {
                                mv.visitTypeInsn(Opcodes.CHECKCAST, instruction.candidate.oxideReturnType.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
                            }
                        }
                    } else {
                        mv.loadAndBox(instruction.candidate, instruction.args, stackMap)
                        instruction.candidate.generateCall(mv)
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
            instruction.candidate.generateCall(mv)
            if (instruction.candidate.hasGenericReturnType) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, instruction.candidate.oxideReturnType.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
            }
        }


    }

    stackMap.pop(instruction.candidate.oxideArgs.size) //args + instance
    stackMap.push(instruction.candidate.oxideReturnType)
}


fun MethodVisitor.loadAndBox(candidate: FunctionCandidate, args: List<TypedInstruction>, stackMap: StackMap) {
    val iter = args.zip(if (args.size == candidate.jvmArgs.size)
        candidate.jvmArgs
    else if (candidate.jvmArgs.size == 1)
        emptyList()
    else
        candidate.jvmArgs.subList(1, candidate.jvmArgs.size)
    )
    for ((ins, type) in iter) {
        compileInstruction(this, ins, stackMap)
        if (type != ins.type) {
            if (ins.type.isInt() && type.isDouble()) {
                visitInsn(Opcodes.I2D)
                continue
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
        is Type.BoolT, Type.IntT -> visitInsn(Opcodes.ICONST_0)
        Type.DoubleT -> visitLdcInsn(0.0)
        is Type.BasicJvmType, Type.Null, is Type.Union, is Type.Array, Type.Never -> visitInsn(Opcodes.ACONST_NULL)
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
    val secondType = instruction.second.type
    when(instruction.op) {
        CompareOp.Eq, CompareOp.Neq -> {
            when {
                firstType.isNumType() && secondType.isNumType() ->
                    compileNumberComparison(mv, instruction.first, instruction.second, instruction.op, stackMap)

                //when we compare a primitive non-number to a number, it will always be false, so we can evaluate that at compiletime:
                firstType.isUnboxedPrimitive() && !firstType.isNumType() && secondType.isNumType() ->

                    mv.visitInsn(Opcodes.ICONST_0).also { stackMap.push(Type.BoolUnknown) }
                secondType.isUnboxedPrimitive() && !secondType.isNumType() && firstType.isNumType() ->
                    mv.visitInsn(Opcodes.ICONST_0).also { stackMap.push(Type.BoolUnknown) }

                //this should only be called when there are 2 unboxed booleans since every other case was handled already
                firstType.isUnboxedPrimitive() && secondType.isUnboxedPrimitive() -> {
                    //sanity-check
                    assert(firstType is Type.BoolT && secondType is Type.BoolT)
                    compileInstruction(mv, instruction.first, stackMap)
                    compileInstruction(mv, instruction.second, stackMap)
                    stackMap.pop(2)
                    val toElse = Label()
                    val end = Label()
                    mv.visitJumpInsn(Opcodes.IF_ICMPNE, toElse)
                    mv.visitInsn(if (instruction.op == CompareOp.Eq) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                    mv.visitJumpInsn (Opcodes.GOTO, end)
                    mv.visitLabel(toElse)
                    stackMap.generateFrame(mv)
                    mv.visitInsn(if (instruction.op == CompareOp.Eq) Opcodes.ICONST_0 else Opcodes.ICONST_1)
                    mv.visitLabel(end)
                    stackMap.push(Type.BoolUnknown)
                    stackMap.generateFrame(mv)

                }
                //is always true since Null is a singleton type
                secondType == Type.Null && firstType == Type.Null -> {
                    stackMap.push(Type.BoolUnknown)
                    mv.visitInsn(if (instruction.op == CompareOp.Eq) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                }
                //unboxed primitives can never be null
                secondType == Type.Null && firstType.isUnboxedPrimitive()-> {
                    stackMap.push(Type.BoolUnknown)
                    mv.visitInsn(if (instruction.op == CompareOp.Neq) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                }
                //unboxed primitives can never be null
                firstType == Type.Null && secondType.isUnboxedPrimitive()-> {
                    stackMap.push(Type.BoolUnknown)
                    mv.visitInsn(if (instruction.op == CompareOp.Neq) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                }
                secondType == Type.Null -> {
                    compileInstruction(mv, instruction.first, stackMap)
                    val elseBody = Label()
                    val end = Label()
                    mv.visitJumpInsn(Opcodes.IFNULL, elseBody)
                    stackMap.pop()
                    mv.visitInsn(if (instruction.op == CompareOp.Neq) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                    mv.visitJumpInsn(Opcodes.GOTO, end)
                    mv.visitLabel(elseBody)
                    stackMap.generateFrame(mv)
                    mv.visitInsn(if (instruction.op == CompareOp.Neq) Opcodes.ICONST_0 else Opcodes.ICONST_1)
                    mv.visitLabel(end)
                    stackMap.push(Type.BoolUnknown)
                    stackMap.generateFrame(mv)
                }
                firstType == Type.Null -> {
                    compileInstruction(mv, instruction.second, stackMap)
                    val elseBody = Label()
                    val end = Label()
                    mv.visitJumpInsn(Opcodes.IFNULL, elseBody)
                    stackMap.pop()
                    mv.visitInsn(if (instruction.op == CompareOp.Neq) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                    mv.visitJumpInsn(Opcodes.GOTO, end)
                    mv.visitLabel(elseBody)
                    stackMap.generateFrame(mv)
                    mv.visitInsn(if (instruction.op == CompareOp.Neq) Opcodes.ICONST_0 else Opcodes.ICONST_1)
                    mv.visitLabel(end)
                    stackMap.push(Type.BoolUnknown)
                    stackMap.generateFrame(mv)
                }
                //complex objects so we have a .equals method
                else -> {
                    compileInstruction(mv, instruction.first, stackMap)
                    //boxes the element if unboxed, and if it's a boxed-type or a complex type it does nothing
                    boxOrIgnore(mv, firstType)
                    compileInstruction(mv, instruction.second, stackMap)
                    //boxes the element if unboxed, and if it's a boxed-type or a complex type it does nothing
                    boxOrIgnore(mv, instruction.second.type)
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        firstType.asBoxed().toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                        "equals",
                        "(Ljava/lang/Object;)Z",
                        false
                    )
                    stackMap.pop(2)
                    stackMap.push(Type.BoolUnknown)
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
    if (firstType.isBoxedPrimitive()) {
        stackMap.pop()
        stackMap.push(secondType.asUnboxed())
        unbox(mv, firstType)
    }
    if (firstType.isInt() && !isIntCmp) {
        mv.visitInsn(Opcodes.I2D)
    }
    compileInstruction(mv, second, stackMap)
    if (secondType.isBoxedPrimitive()) {
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
    stackMap.push(Type.BoolUnknown)
    stackMap.generateFrame(mv)

}

fun Type.asUnboxed() = when (this) {
    is Type.BoolT, Type.Bool-> Type.BoolUnknown
    Type.DoubleT, Type.Double -> Type.DoubleT
    Type.IntT, Type.Int -> Type.IntT
    else -> error("Cannot unbox $this")
}
