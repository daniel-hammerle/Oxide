package com.language.codegen

import com.language.BooleanOp
import com.language.CompareOp
import com.language.MathOp
import com.language.compilation.*
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.Stack

class CleanUpFrame(val cleanUp: (Boolean) -> Unit) //bool decides whether the escape operation has a value on the stack

class CleanupStack(private val tasks: MutableList<CleanUpFrame>) {
    fun useBreak(withValueOnStack: Boolean) {
        tasks.last().cleanUp(withValueOnStack)
    }

    fun useReturn(withValueOnStack: Boolean) {
        tasks.forEach { it.cleanUp(withValueOnStack) }
    }

    fun push(clean: CleanUpFrame) {
        tasks.add(clean)
    }

    fun pop() {
        tasks.removeLast()
    }
}

fun compileInstruction(mv: MethodVisitor, instruction: TypedInstruction, stackMap: StackMap, clean: CleanupStack) {
    when(instruction) {
        is TypedInstruction.DynamicCall -> compileDynamicCall(mv, instruction, stackMap, clean)
        is TypedInstruction.Noop -> {}
        is TypedInstruction.Ignore -> {
            compileInstruction(mv, instruction.other, stackMap, clean)
            if (instruction.other.type !in listOf(Type.Nothing, Type.Never)) {
                mv.visitInsn(Opcodes.POP)
            }
        }
        is TypedInstruction.InlineBody -> {
            compileInstruction(mv, instruction.body, stackMap, CleanupStack(mutableListOf()))
            if (instruction.body.type != Type.Nothing) {
                stackMap.pop()
            }
            stackMap.push(instruction.type)
            mv.visitLabel(instruction.endLabel)
            if (!instruction.body.pushesStackFrame())
                stackMap.generateFrame(mv)
        }
        is TypedInstruction.DynamicPropertyAccess -> {
            //load instance onto the stack
            compileInstruction(mv, instruction.parent, stackMap, clean)
            when(instruction.parent.type) {
                is Type.Union -> {
                    mv.dynamicDispatch((instruction.parent.type as Type.Union).entries, elseType = instruction.type, stackMap) { type ->
                        mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            type.toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                            instruction.name,
                            instruction.physicalType.toJVMDescriptor()
                        )
                    }
                }
                else -> {
                    mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        instruction.parent.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                        instruction.name,
                        instruction.physicalType.toJVMDescriptor()
                    )
                    if (instruction.physicalType != instruction.type) {
                        mv.visitTypeInsn(Opcodes.CHECKCAST, instruction.type.asBoxed().toJVMDescriptor().removePrefix("L").removeSuffix(";"))
                        unboxOrIgnore(mv, instruction.type.asBoxed(), instruction.type)
                    }
                }
            }
            stackMap.pop() //pop instance
            if (instruction.type != instruction.physicalType) {
                if (instruction.type.isUnboxedPrimitive()) {
                    unboxOrIgnore(mv, instruction.physicalType, instruction.type)
                } else {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, instruction.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
                }
            }
            stackMap.push(instruction.type)
        }
        is TypedInstruction.DynamicPropertyAssignment -> {
            //load instance onto the stack
            compileInstruction(mv, instruction.parent, stackMap, clean)
            compileInstruction(mv, instruction.value, stackMap, clean)

            if (instruction.value.type != instruction.physicalType) {
                boxOrIgnore(mv, instruction.value.type)
                unboxOrIgnore(mv, instruction.value.type, instruction.physicalType)
            }

            stackMap.pop(2)
            mv.visitFieldInsn(
                Opcodes.PUTFIELD,
                instruction.parent.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"),
                instruction.name,
                instruction.physicalType.toJVMDescriptor()
            )
        }
        is TypedInstruction.If -> compileIf(mv, instruction, stackMap, clean)
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
            val ins = loadInstruction(instruction.type)
            mv.visitVarInsn(ins, instruction.id)
            stackMap.push(instruction.type)
        }
        is TypedInstruction.StoreVar -> {
            compileInstruction(mv, instruction.value, stackMap, clean)
            val ins = storeInstruction(instruction.value.type)
            mv.visitVarInsn(ins, instruction.id)
            stackMap.changeVar(instruction.id, instruction.value.type)
            //storing removes an item from the stack
            stackMap.pop()
        }
        is TypedInstruction.Math -> {
            val secondType = instruction.second.type
            val firstType = instruction.first.type

            if (instruction.op == MathOp.Add && instruction.first.type == Type.String) {
                compileInstruction(mv, instruction.first, stackMap, clean)
                compileInstruction(mv, instruction.second, stackMap, clean)
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
                        compileInstruction(mv, instruction.first, stackMap, clean)
                        compileInstruction(mv, instruction.second, stackMap, clean)
                    }
                    firstType.isDouble() -> {
                        compileInstruction(mv, instruction.first, stackMap, clean)
                        compileInstruction(mv, instruction.second, stackMap, clean)
                        mv.visitInsn(Opcodes.I2D)
                    }
                    secondType.isDouble() -> {
                        compileInstruction(mv, instruction.first, stackMap, clean)
                        mv.visitInsn(Opcodes.I2D)
                        compileInstruction(mv, instruction.second, stackMap, clean)
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
                    compileInstruction(mv, instruction.first, stackMap, clean)
                    if (firstType != Type.DoubleT)
                        mv.visitInsn(Opcodes.I2D)
                    compileInstruction(mv, instruction.second, stackMap, clean)
                    if (secondType != Type.DoubleT)
                        mv.visitInsn(Opcodes.I2D)
                    mv.visitInsn(Opcodes.DDIV)
                    return
                }
                compileInstruction(mv, instruction.first, stackMap, clean)
                unboxOrIgnore(mv, instruction.first.type, instruction.first.type.asUnboxed())
                compileInstruction(mv, instruction.second, stackMap, clean)
                unboxOrIgnore(mv, instruction.second.type, instruction.first.type.asUnboxed())
                mv.visitInsn(when (instruction.op) {
                    MathOp.Add -> Opcodes.IADD
                    MathOp.Sub -> Opcodes.ISUB
                    MathOp.Mul -> Opcodes.IMUL
                    MathOp.Div -> error("Unreachable")
                })
                stackMap.pop(2)
                stackMap.push(Type.IntT)
            }
        }
        is TypedInstruction.Comparing -> {
            compileComparison(mv, instruction, stackMap, clean)
        }
        is TypedInstruction.StaticCall -> {
            //load args
            mv.loadAndBox(instruction.candidate, instruction.args, stackMap, clean)

            instruction.candidate.generateCall(mv, stackMap)
            stackMap.pop(instruction.candidate.oxideArgs.size)
            if (instruction.candidate.oxideReturnType == Type.Never) {
                neverAssertException(mv)
            }

            stackMap.push(instruction.candidate.oxideReturnType)
        }
        is TypedInstruction.ModuleCall -> {
            //load args
            mv.loadAndBox(instruction.candidate, instruction.args, stackMap, clean)

            instruction.candidate.generateCall(mv, stackMap)
            stackMap.pop(instruction.candidate.oxideArgs.size)

            if (instruction.candidate.oxideReturnType == Type.Never) {
                neverAssertException(mv)
            }
            stackMap.push(instruction.candidate.oxideReturnType)
        }
        is TypedInstruction.MultiInstructions -> {
            stackMap.pushVarFrame(instruction.varFrame)
            //stackMap.generateFrame(mv)
            instruction.instructions.forEachIndexed { index, it ->
                compileInstruction(mv, it, stackMap, clean)
                //if we leave garbage on the stack and are not the last instruction (which would return)
                if (it.type !in listOf(Type.Nothing, Type.Never) && index != instruction.instructions.lastIndex){
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
            runCatching { stackMap.generateFrame(mv) }
            if (!instruction.infinite) {
                compileInstruction(mv, instruction.cond, stackMap, clean)
                //if the condition is false (equal to 0), jump to end
                mv.visitJumpInsn(Opcodes.IFEQ, end)
                stackMap.pop()
            }

            //compile body
            compileInstruction(mv, instruction.body, stackMap, clean)

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
            if (instruction.infinite) {
                neverAssertException(mv)
            }
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
            mv.loadAndBox(instruction.candidate, instruction.args, stackMap, clean)
            //call constructor
            instruction.candidate.generateCall(mv, stackMap)
            stackMap.pop(instruction.args.size)
            stackMap.push(instruction.candidate.oxideReturnType)
        }
        is TypedInstruction.Match -> compileMatch(mv, instruction, stackMap, clean)
        is TypedInstruction.LoadList -> {

            mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
            stackMap.push(instruction.type)
            mv.visitInsn(Opcodes.DUP)
            stackMap.dup()
            if (instruction.isConstList) {
                compileInstruction(mv, TypedInstruction.LoadConstInt(instruction.items.size), stackMap, clean)
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

                when(item) {
                    is TypedConstructingArgument.Collected -> {
                        compileInstruction(mv, item.instruction, stackMap, clean)

                        //if it's an array, convert it to a collection first
                        if (item.type is Type.Array) {
                            mv.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "java/util/Arrays",
                                "asList",
                                "([Ljava/lang/Object;)Ljava/util/List;",
                                false
                            )
                        }

                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "addAll", "(Ljava/util/Collection;)Z", false)
                        mv.visitInsn(Opcodes.POP)
                        stackMap.pop(2)
                    }
                    is TypedConstructingArgument.Normal -> {
                        compileInstruction(mv, item.instruction, stackMap, clean)
                        boxOrIgnore(mv, item.type)
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false)
                        mv.visitInsn(Opcodes.POP)

                        stackMap.pop(2)
                    }
                    is TypedConstructingArgument.Iteration -> {
                        mv.visitVarInsn(Opcodes.ASTORE, instruction.tempArrayVariable!!)
                        stackMap.changeVar(instruction.tempArrayVariable, Type.BasicJvmType(SignatureString("java::util::ArrayList")))
                        stackMap.pop()
                        compileForLoop(mv, item.instruction, stackMap, clean) {
                            //box the item of the body if necessary
                            boxOrIgnore(mv, item.instruction.body.type)
                            mv.visitVarInsn(Opcodes.ALOAD, instruction.tempArrayVariable)
                            mv.visitInsn(Opcodes.SWAP)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false)
                            mv.visitInsn(Opcodes.POP)
                            stackMap.pop()
                        }
                    }
                }

            }
        }
        is TypedInstruction.LoadConstArray -> {
            compileInstruction(mv, TypedInstruction.LoadConstInt(instruction.items.size), stackMap, clean)
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
                compileInstruction(mv, TypedInstruction.LoadConstInt(index), stackMap, clean)
                compileInstruction(mv, item, stackMap, clean)
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

            if (instruction.items.size == 1) {
                when(val item = instruction.items.first()) {
                    is TypedConstructingArgument.Collected -> {
                        when(item.instruction.type) {
                            is Type.Array -> {
                                compileInstruction(mv, item.instruction, stackMap, clean)
                                stackMap.pop()
                                mv.visitMethodInsn(
                                    Opcodes.INVOKEVIRTUAL,
                                    "[Ljava/lang/Object;",
                                    "clone",
                                    "()Ljava/lang/Object;",
                                    false
                                )
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;")
                            }
                            is Type.JvmType -> {
                                compileInstruction(mv, item.instruction, stackMap, clean)
                                stackMap.pop()
                                mv.visitMethodInsn(
                                    Opcodes.INVOKEINTERFACE,
                                    "java/util/Collection",
                                    "toArray",
                                    "()[Ljava/lang/Object;",
                                    true
                                )
                            }
                            else -> error("Unreachable")
                        }

                        stackMap.push(Type.Array(instruction.itemType))

                        return
                    }
                    is TypedConstructingArgument.Iteration -> {
                        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            "java/util/ArrayList",
                            "<init>",
                            "()V",
                            false
                        )
                        //temporarily store it
                        mv.visitVarInsn(Opcodes.ASTORE, instruction.tempArrayVarId)
                        stackMap.changeVar(instruction.tempArrayVarId, Type.BasicJvmType(SignatureString("java::util::ArrayList")))

                        //compile the for-loop and add the resulting items to the arraylist
                        compileForLoop(mv, item.instruction, stackMap, clean) {
                            mv.visitVarInsn(Opcodes.ALOAD, instruction.tempArrayVarId)
                            mv.visitInsn(Opcodes.SWAP)
                            boxOrIgnore(mv, item.instruction.body.type)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false)
                            mv.visitInsn(Opcodes.POP)
                            stackMap.pop()
                        }

                        //Convert the ArrayList into an array
                        mv.visitVarInsn(Opcodes.ALOAD, instruction.tempArrayVarId)
                        mv.visitInsn(Opcodes.ICONST_0)
                        val itemType =  item.type.asBoxed().toJVMDescriptor().removePrefix("L").removeSuffix(";")
                        mv.visitTypeInsn(Opcodes.ANEWARRAY, itemType)
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/util/ArrayList",
                            "toArray",
                            "([Ljava/lang/Object;)[Ljava/lang/Object;",
                            false
                        )
                        stackMap.push(Type.Array(instruction.itemType))

                        return
                    }
                    is TypedConstructingArgument.Normal -> {}
                }
            }

            //load the number of items on the stack
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitVarInsn(Opcodes.ISTORE, instruction.tempIndexVarId)
            stackMap.changeVar(instruction.tempIndexVarId, Type.IntT)
            var itemCount = 0
            for (item in instruction.items) {
                when(item) {

                    is TypedConstructingArgument.Collected -> {
                        compileInstruction(mv, item.instruction, stackMap, clean)
                        if (instruction.arrayType == ArrayType.Object) {
                            boxOrIgnore(mv, item.instruction.type)
                        }
                        //make `stackmap` contain the item, itemLength
                        stackMap.push(Type.IntT)

                        //Now let's make the actual bytecode also contain item, itemLength
                        //item
                        mv.visitInsn(Opcodes.DUP)
                        //item item
                        when(item.type) {
                            is Type.JvmArray -> {
                                mv.visitInsn(Opcodes.ARRAYLENGTH)
                                //item itemLength
                                mv.visitInsn(Opcodes.DUP)
                                //item itemLength, itemLength

                                //This will then be added and results in
                                //item itemLength, newDLength
                            }
                            else -> TODO()
                        }
                        mv.visitVarInsn(Opcodes.ILOAD, instruction.tempIndexVarId)
                        mv.visitInsn(Opcodes.IADD)
                        mv.visitVarInsn(Opcodes.ISTORE, instruction.tempIndexVarId)
                    }
                    is TypedConstructingArgument.Normal -> {
                        compileInstruction(mv, item.instruction, stackMap, clean)
                        if (instruction.arrayType == ArrayType.Object) {
                            boxOrIgnore(mv, item.instruction.type)
                        }
                        itemCount++
                    }

                    //The strategy here is
                    // to create an ArrayList to temporarily store the results of the for loop
                    // and then
                    // copy its contents to an array
                    // (and then it can be treated like the collect variant
                    is TypedConstructingArgument.Iteration -> {
                        //create new arraylist
                        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            "java/util/ArrayList",
                            "<init>",
                            "()V",
                            false
                        )
                        //temporarily store it
                        mv.visitVarInsn(Opcodes.ASTORE, instruction.tempArrayVarId)
                        stackMap.changeVar(instruction.tempArrayVarId, Type.BasicJvmType(SignatureString("java::util::ArrayList")))

                        //compile the for-loop and add the resulting items to the arraylist
                        compileForLoop(mv, item.instruction, stackMap, clean) {
                            mv.visitVarInsn(Opcodes.ALOAD, instruction.tempArrayVarId)
                            mv.visitInsn(Opcodes.SWAP)
                            boxOrIgnore(mv, item.instruction.body.type)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false)
                            mv.visitInsn(Opcodes.POP)
                            stackMap.pop()
                        }

                        //Convert the ArrayList into an array
                        mv.visitVarInsn(Opcodes.ALOAD, instruction.tempArrayVarId)
                        mv.visitInsn(Opcodes.ICONST_0)
                        val itemType =  item.type.asBoxed().toJVMDescriptor().removePrefix("L").removeSuffix(";")
                        mv.visitTypeInsn(Opcodes.ANEWARRAY, itemType)
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/util/ArrayList",
                            "toArray",
                            "([Ljava/lang/Object;)[Ljava/lang/Object;",
                            false
                        )
                       // mv.visitTypeInsn(Opcodes.CHECKCAST, Type.Array(Type.BroadType.Known(item.type)).toJVMDescriptor())

                        mv.visitInsn(Opcodes.DUP)
                        mv.visitInsn(Opcodes.ARRAYLENGTH)
                        mv.visitInsn(Opcodes.DUP)
                        stackMap.push(Type.IntT)
                        mv.visitVarInsn(Opcodes.ILOAD, instruction.tempIndexVarId)
                        mv.visitInsn(Opcodes.IADD)
                        mv.visitVarInsn(Opcodes.ISTORE, instruction.tempIndexVarId)
                    }
                }

            }

            for (item in instruction.items) {
                stackMap.pop()
                when(item) {
                    is TypedConstructingArgument.Collected -> stackMap.pop()
                    is TypedConstructingArgument.Normal -> {}
                    is TypedConstructingArgument.Iteration -> {}
                }
            }
            mv.visitIincInsn(instruction.tempIndexVarId, itemCount)
            mv.visitVarInsn(Opcodes.ILOAD, instruction.tempIndexVarId)
            when(instruction.arrayType) {
                ArrayType.Int -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
                ArrayType.Double -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE)
                ArrayType.Bool -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN)
                ArrayType.Object -> mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
            }
            mv.visitIincInsn(instruction.tempIndexVarId, -1)
            mv.visitVarInsn(Opcodes.ASTORE, instruction.tempArrayVarId)
            stackMap.changeVar(instruction.tempArrayVarId, instruction.type)
            //NOTE: The following comments represent the items on the stack.
            //The further right an item is, the closer it is to the top of the stack

            //item
            instruction.items.asReversed().forEachIndexed { i, item ->
                when(item) {
                    is TypedConstructingArgument.Normal -> {
                        //item
                        mv.visitVarInsn(Opcodes.ALOAD, instruction.tempArrayVarId)
                        //item instance
                        mv.visitInsn(Opcodes.SWAP)
                        mv.visitVarInsn(Opcodes.ILOAD, instruction.tempIndexVarId)
                        mv.visitInsn(Opcodes.SWAP)

                        when(instruction.arrayType) {
                            ArrayType.Int -> mv.visitInsn(Opcodes.IASTORE)
                            ArrayType.Double -> mv.visitInsn(Opcodes.DASTORE)
                            ArrayType.Bool -> mv.visitInsn(Opcodes.IASTORE)
                            ArrayType.Object -> mv.visitInsn(Opcodes.AASTORE)
                        }

                        mv.visitIincInsn(instruction.tempIndexVarId, -1)
                    }
                    is TypedConstructingArgument.Collected, is TypedConstructingArgument.Iteration -> {
                        //srcArray srcLength
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitInsn(Opcodes.SWAP)
                        //srcArray, 0, srcLength
                        mv.visitVarInsn(Opcodes.ALOAD, instruction.tempArrayVarId)
                        mv.visitInsn(Opcodes.SWAP)
                        //srcArray, 0, instance, srcLength
                        mv.visitInsn(Opcodes.DUP)
                        //srcArray, 0, instance, srcLength, srcLength
                        mv.visitIincInsn(instruction.tempIndexVarId, 1)
                        mv.visitVarInsn(Opcodes.ILOAD, instruction.tempIndexVarId)
                        mv.visitInsn(Opcodes.SWAP)
                        mv.visitInsn(Opcodes.ISUB)
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitVarInsn(Opcodes.ISTORE, instruction.tempIndexVarId)
                        mv.visitInsn(Opcodes.SWAP)
                        //newIndex, srcArray, 0, instance, newIndex, srcLength
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false)
                    }
                }

            }
            mv.visitVarInsn(Opcodes.ALOAD, instruction.tempArrayVarId)
            stackMap.push(Type.Array(instruction.itemType))
        }

        is TypedInstruction.ForLoop -> {
            compileForLoop(mv, instruction, stackMap, clean) {
                when (instruction.body.type) {
                    Type.Nothing -> {}
                    Type.Never -> {}
                    else -> {
                        stackMap.pop()
                        mv.visitInsn(Opcodes.POP)
                    }
                }
            }
        }
        is TypedInstruction.Try -> {
            compileInstruction(mv, instruction.parent, stackMap, clean)
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


        is TypedInstruction.Return -> {
            compileInstruction(mv, instruction.returnValue, stackMap, clean)
            if (instruction.returnValue.type != Type.Nothing && instruction.returnValue.type != Type.Never)
                stackMap.pop()
            if (instruction.label == null) {
                mv.visitInsn(returnInstruction(instruction.returnValue.type))
            } else {
                clean.useReturn(instruction.returnValue.type != Type.Nothing)
                mv.visitJumpInsn(Opcodes.GOTO, instruction.label)
            }
        }

        is TypedInstruction.Keep -> {
            mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                instruction.parentName.toJvmNotation(),
                instruction.fieldName,
                instruction.type.toJVMDescriptor()
            )
            stackMap.push(instruction.value.type)
            val skip = Label()
            mv.visitInsn(Opcodes.DUP)
            mv.visitJumpInsn(Opcodes.IFNONNULL, skip)
            mv.visitInsn(Opcodes.POP)
            stackMap.pop()

            compileInstruction(mv, instruction.value, stackMap, clean)
            boxOrIgnore(mv, instruction.type)
            mv.visitInsn(Opcodes.DUP)
            mv.visitFieldInsn(
                Opcodes.PUTSTATIC,
                instruction.parentName.toJvmNotation(),
                instruction.fieldName,
                instruction.type.toJVMDescriptor()
            )
            stackMap.generateFrame(mv)
            mv.visitLabel(skip)
        }

        is TypedInstruction.Lambda -> {
            mv.visitTypeInsn(Opcodes.NEW, instruction.signatureString.toJvmNotation())
            stackMap.push(instruction.type)
            mv.visitInsn(Opcodes.DUP)
            stackMap.push(instruction.type)
            instruction.captures.values.forEach { ins ->
                compileInstruction(mv, ins, stackMap, clean)
            }
            instruction.constructorCandidate.generateCall(mv, stackMap)
            stackMap.pop(instruction.captures.size + 1)
        }

        is TypedInstruction.LoadConstConstArray -> {
            compileInstruction(mv, TypedInstruction.LoadConstInt(instruction.items.size), stackMap, clean)
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
                compileInstruction(mv, TypedInstruction.LoadConstInt(index), stackMap, clean)
                compileInstruction(mv, item, stackMap, clean)
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
        is TypedInstruction.Not -> {

            val skip = Label()
            val end = Label()
            compileInstruction(mv, instruction.ins, stackMap, clean)
            mv.visitJumpInsn(Opcodes.IFNE, skip) //jump to load 0 when condition is true
            stackMap.pop()
            mv.visitInsn(Opcodes.ICONST_1) //load true if jump not completed
            mv.visitJumpInsn(Opcodes.GOTO, end) //bypass loading 0
            mv.visitLabel(skip)
            stackMap.generateFrame(mv)
            mv.visitInsn(Opcodes.ICONST_0)
            stackMap.push(Type.BoolUnknown)
            mv.visitLabel(end)
            stackMap.generateFrame(mv)
        }
        is TypedInstruction.LogicOperation -> {
            val condIns = when(instruction.op) {
                BooleanOp.And -> Opcodes.IFEQ
                BooleanOp.Or -> Opcodes.IFNE
            }

            val condMetLabel = Label()
            val skipLabel = Label()

            //load and jump for short-circuiting if needed
            compileInstruction(mv, instruction.first, stackMap, clean)
            mv.visitJumpInsn(condIns, condMetLabel)
            stackMap.pop()

            //load and jump for short-circuiting if needed
            compileInstruction(mv, instruction.second, stackMap, clean)
            mv.visitJumpInsn(condIns, condMetLabel)
            stackMap.pop()
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitJumpInsn(Opcodes.GOTO, skipLabel)
            mv.visitLabel(condMetLabel)
            stackMap.generateFrame(mv)
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitLabel(skipLabel)
            stackMap.push(Type.BoolUnknown)
            stackMap.generateFrame(mv)

        }
    }
}

inline fun compileForLoop(
    mv: MethodVisitor,
    instruction: TypedInstruction.ForLoop,
    stackMap: StackMap,
    clean: CleanupStack,
    endOfBodyTask: () -> Unit
) {
    compileInstruction(mv, instruction.parent, stackMap, clean)
    val start = Label()
    val end = Label()
    //create a new var frame so that the variables like the index are gone after the loop's execution
    stackMap.pushVarFrame(instruction.bodyFrame, cloning = false)

    if (instruction.indexId != null) {
        mv.visitInsn(Opcodes.ICONST_0)
        stackMap.changeVar(instruction.indexId, Type.IntT)
        mv.visitVarInsn(Opcodes.ISTORE, instruction.indexId)
    }

    compileScopeAdjustment(mv, instruction.preLoopAdjustments, stackMap, clean)
    clean.push(CleanUpFrame {
        if (it) {
            mv.visitInsn(Opcodes.SWAP)
        }
        mv.visitInsn(Opcodes.POP)
    })
    mv.visitLabel(start)
    stackMap.generateFrame(mv)
    mv.visitInsn(Opcodes.DUP)
    instruction.hasNextCall.generateCall(mv, stackMap)
    mv.visitJumpInsn(Opcodes.IFEQ, end)
    mv.visitInsn(Opcodes.DUP)
    instruction.nextCall.generateCall(mv, stackMap)
    val storeInstruction = when(instruction.nextCall.oxideReturnType) {
        is Type.IntT -> Opcodes.ISTORE
        is Type.BoolT -> Opcodes.ISTORE
        is Type.DoubleT -> Opcodes.DSTORE
        else -> Opcodes.ASTORE
    }
    mv.visitVarInsn(storeInstruction, instruction.itemId)
    stackMap.changeVar(instruction.itemId, instruction.nextCall.oxideReturnType)

    compileInstruction(mv, instruction.body.instruction, stackMap, clean)
    endOfBodyTask()
    if (instruction.indexId != null) {
        mv.visitIincInsn(instruction.indexId, 1)
    }
    mv.visitJumpInsn(Opcodes.GOTO, start)
    mv.visitLabel(end)
    clean.pop()
    stackMap.popVarFrame()

    //pop the previously pushed var frame so that all the for-loop-stuff is gone
    stackMap.generateFrame(mv)

    //cleanup (pop the iterator from the stack)
    mv.visitInsn(Opcodes.POP)
    stackMap.pop()
    compileScopeAdjustment(mv, instruction.postLoopAdjustments, stackMap, clean)
}

fun compileIf(
    mv: MethodVisitor,
    instruction: TypedInstruction.If,
    stackMap: StackMap,
    clean: CleanupStack,
    nested: Boolean = false
) {
    //eval condition
    compileInstruction(mv, instruction.cond, stackMap, clean)

    val betweenBodyAndElseBody = Label()
    val afterElseBody = Label()
    //if the condition is false goto else
    mv.visitJumpInsn(Opcodes.IFEQ, betweenBodyAndElseBody)
    //the ifeq removes the bool from the stack
    stackMap.pop()
    //body
    compileInstruction(mv, instruction.body, stackMap, clean)
    if (instruction.type != instruction.body.type) {
        boxOrIgnore(mv, instruction.body.type)
    }
    compileScopeAdjustment(mv, instruction.bodyAdjust, stackMap, clean)

    //pop the result of the other branch for now
    if (instruction.body.type != Type.Nothing && instruction.body.type != Type.Never) {
        stackMap.pop()
    }

    if (instruction.elseBody == null && instruction.body.type == Type.Nothing) {
        mv.visitLabel(betweenBodyAndElseBody)
        stackMap.adaptFrame(instruction.varFrame)

        stackMap.generateFrame(mv)
        return
    }

    if (instruction.body.type != Type.Never) {
        //skip else body when body was executed
        //if a type is never we don't need that
        mv.visitJumpInsn(Opcodes.GOTO, afterElseBody)
    }

    stackMap.generateFrame(mv)

    mv.visitLabel(betweenBodyAndElseBody)
    //else body
    if (instruction.elseBody is TypedInstruction.If) {
        compileIf(mv, instruction.elseBody, stackMap, clean, nested = true)
    } else {
        compileInstruction(mv, instruction.elseBody ?: TypedInstruction.Null, stackMap, clean)
    }
    if (instruction.type != instruction.elseBody?.type) {
        boxOrIgnore(mv, instruction.elseBody?.type ?: Type.Null)
    }
    compileScopeAdjustment(mv, instruction.elseBodyAdjust, stackMap, clean)
    //label after else body
    mv.visitLabel(afterElseBody)

    if (instruction.elseBody?.type != Type.Nothing && instruction.elseBody?.type != Type.Never) {
        stackMap.pop()
    }
    stackMap.push(instruction.type)

    stackMap.adaptFrame(instruction.varFrame)
    if (!nested)
    stackMap.generateFrame(mv)
}

fun compileMatch(
    mv: MethodVisitor,
    instruction: TypedInstruction.Match,
    stackMap: StackMap,
    clean: CleanupStack
) {
    //load the matchee into the temporary variable
    compileInstruction(mv, TypedInstruction.StoreVar(instruction.temporaryId, instruction.parent), stackMap, clean)

    val end = Label()
    for ((pattern, body, scopeAdjustment, varFrame) in instruction.patterns) {
        stackMap.generateFrame(mv)
        stackMap.pushVarFrame(varFrame, cloning = false)

        val patternFail = Label()
        compilePattern(mv, pattern, stackMap, patternFail, clean)
        compileInstruction(mv, body, stackMap, clean)
        /*


         */
        if (instruction.type != body.type) {
            boxOrIgnore(mv, body.type)
            unboxOrIgnore(mv, body.type, instruction.type)
        }

        if (body.type != Type.Never) {
            compileScopeAdjustment(mv, scopeAdjustment, stackMap, clean)
            mv.visitJumpInsn(Opcodes.GOTO, end)
        }

        if (body.type !in listOf(Type.Nothing, Type.Never)) {
            //we pop the value produced by the body in this loop since we dont need it rn
            //on the stackmap however, we keep it in the actual bytecode
            stackMap.pop()
        }


        mv.visitLabel(patternFail)
        stackMap.popVarFrame()
    }
    //Fall-through-branch which is only reachable on type system manipulation or compiler errors
    stackMap.generateFrame(mv)
    mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException")
    mv.visitInsn(Opcodes.DUP)
    mv.visitLdcInsn(
        "Argument supplied to match statement does not fit its assigned type constraints. " +
            "This can be through invalid unchecked casting or a compiler error"
    )
    mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "java/lang/IllegalArgumentException",
        "<init>",
        "(Ljava/lang/String;)V",
        false
    )
    mv.visitInsn(Opcodes.ATHROW)

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
    patternFail: Label,
    clean: CleanupStack
) {
    when(pattern) {
        is TypedIRPattern.Binding -> {
            //gracefully ignore since there is nothing that actually needs to be done
        }
        is TypedIRPattern.Condition -> {
            compilePattern(mv, pattern.parent, stackMap, patternFail, clean)
            compileInstruction(mv, pattern.condition, stackMap, clean)
            //if condition equal to is 0 (false) go to the fail stage
            val skip = Label()
            mv.visitJumpInsn(Opcodes.IFNE, skip)
            mv.visitJumpInsn(Opcodes.GOTO, patternFail)
            mv.visitLabel(skip)
            stackMap.pop()
            stackMap.generateFrame(mv)
        }
        is TypedIRPattern.Destructuring -> compilePatternDestructuring(mv, pattern, stackMap, patternFail, clean)
    }
}


fun compilePatternDestructuring(
    mv: MethodVisitor,
    pattern: TypedIRPattern.Destructuring,
    stackMap: StackMap,
    patternFail: Label,
    clean: CleanupStack
) {

    val success = Label()
    compileInstruction(mv, pattern.loadItem, stackMap, clean)
    mv.visitInsn(Opcodes.DUP)
    stackMap.dup()
    mv.visitTypeInsn(Opcodes.INSTANCEOF, pattern.forge.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
    stackMap.pop()

    stackMap.push(Type.BoolUnknown)
    mv.visitJumpInsn(Opcodes.IFNE, success)
    stackMap.pop()
    //IF FAIL
    mv.visitInsn(Opcodes.POP)
    stackMap.pop()
    //instance of check failed so go to the fail stage
    mv.visitJumpInsn(Opcodes.GOTO, patternFail)
    //END IF FAIL
    stackMap.push(pattern.loadItem.type)
    //IF SUCCESS
    mv.visitLabel(success)
    stackMap.generateFrame(mv)
    mv.visitTypeInsn(Opcodes.CHECKCAST, pattern.forge.type.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
    stackMap.pop()
    stackMap.push(pattern.forge.type)
    compileInstruction(mv, TypedInstruction.StoreVar(pattern.castStoreId, TypedInstruction.Noop(pattern.forge.type)), stackMap, clean)

    //compile all the desturcturing patterns
    pattern.patterns.forEach {
        compilePattern(mv, it, stackMap, patternFail, clean)
    }
}

fun compileDynamicCall(
    mv: MethodVisitor,
    instruction: TypedInstruction.DynamicCall,
    stackMap: StackMap,
    clean: CleanupStack
) {
    //load instance onto the stack
    compileInstruction(mv, instruction.parent, stackMap, clean)
    //boxOrIgnore(mv, instruction.parent.type)

    mv.loadAndBox(instruction.candidate, instruction.args, stackMap, clean)
    if (instruction.parent.type.isUnboxedPrimitive() && instruction.candidate.jvmOwner.isBoxedPrimitive()) {
        boxOrIgnore(mv, instruction.parent.type)
    }
    instruction.candidate.generateCall(mv, stackMap)

    if (instruction.candidate.oxideReturnType == Type.Never) {
        neverAssertException(mv)
    }

    stackMap.pop(instruction.candidate.oxideArgs.size) //args + instance
    stackMap.push(instruction.candidate.oxideReturnType)
}

fun SignatureString.isBoxedPrimitive(): Boolean {
    return oxideNotation == "java::lang::Integer" || oxideNotation == "java::lang::Double" || oxideNotation == "java::lang::Boolean"
}

fun MethodVisitor.loadAndBox(candidate: FunctionCandidate, args: List<TypedInstruction>, stackMap: StackMap, clean: CleanupStack) {
    val iter = args.zip(if (args.size == candidate.jvmArgs.size)
        candidate.jvmArgs
    else if (candidate.jvmArgs.size == 1)
        emptyList()
    else
        candidate.jvmArgs.subList(1, candidate.jvmArgs.size)
    )
    for ((i, item) in iter.enumerate()) {
        val (ins, type) = item
        val idx = candidate.varargInfo?.first

        if (i == idx) {
            //load count onto the stack
            compileInstruction(this, TypedInstruction.LoadConstInt(candidate.jvmArgs.size - idx), stackMap, clean)
            //create the array
            visitTypeInsn(Opcodes.ANEWARRAY, candidate.varargInfo!!.second.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
        }

        if (idx != null && i >= idx) {
            visitInsn(Opcodes.DUP)
            compileInstruction(this, TypedInstruction.LoadConstInt(i - idx), stackMap, clean)
        }

        compileInstruction(this, ins, stackMap, clean)
        if (type != ins.type) {
            if (ins.type.isInt() && type.isDouble()) {
                visitInsn(Opcodes.I2D)
                continue
            }
            boxOrIgnore(this, ins.type)
            unboxOrIgnore(this, ins.type, type)
        }
        val insType = ins.type
        
        if (insType is Type.Union && type is Type.JvmType && Type.Null in insType.entries && type in insType.entries) {
            visitTypeInsn(Opcodes.CHECKCAST, type.signature.toJvmNotation())
        }

        if (idx != null && i >= idx) {
            visitInsn(Opcodes.AASTORE)
        }
    }
    if (candidate.varargInfo?.first == candidate.jvmArgs.size - 1) {
        visitInsn(Opcodes.ICONST_0)
        visitTypeInsn(Opcodes.ANEWARRAY, candidate.varargInfo!!.second.toJVMDescriptor().removePrefix("L").removeSuffix(";"))
    }
}



inline fun MethodVisitor.dynamicDispatch(types: Iterable<Type>, elseType: Type, stackMap: StackMap, task: (type: Type) -> Unit) {
    val end = Label()

    types.forEach { type ->
        val skip = Label()
        visitInsn(Opcodes.DUP)
        if (type.isUnboxedPrimitive()) {
            error("Cannot dispatch unboxed primitive `$type` in `$types`")
        }
        if (type == Type.Null) {
            visitJumpInsn(Opcodes.IFNONNULL, skip)
        } else {
            visitTypeInsn(Opcodes.INSTANCEOF, type.toJVMDescriptor().removeSuffix(";").removePrefix("L"))
            visitJumpInsn(Opcodes.IFEQ, skip)
        }

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

    neverAssertException(this)
    stackMap.push(elseType)
    visitLabel(end)
    stackMap.generateFrame(this)

}


fun compileComparison(
    mv: MethodVisitor,
    instruction: TypedInstruction.Comparing,
    stackMap: StackMap,
    clean: CleanupStack
) {
    val firstType = instruction.first.type
    val secondType = instruction.second.type
    when(instruction.op) {
        CompareOp.Eq, CompareOp.Neq -> {
            when {
                firstType.isNumType() && secondType.isNumType() ->
                    compileNumberComparison(mv, instruction.first, instruction.second, instruction.op, stackMap, clean)

                //when we compare a primitive non-number to a number, it will always be false, so we can evaluate that at compiletime:
                firstType.isUnboxedPrimitive() && !firstType.isNumType() && secondType.isNumType() ->

                    mv.visitInsn(Opcodes.ICONST_0).also { stackMap.push(Type.BoolUnknown) }
                secondType.isUnboxedPrimitive() && !secondType.isNumType() && firstType.isNumType() ->
                    mv.visitInsn(Opcodes.ICONST_0).also { stackMap.push(Type.BoolUnknown) }

                //this should only be called when there are 2 unboxed booleans since every other case was handled already
                firstType.isUnboxedPrimitive() && secondType.isUnboxedPrimitive() -> {
                    //sanity-check
                    assert(firstType is Type.BoolT && secondType is Type.BoolT)
                    compileInstruction(mv, instruction.first, stackMap, clean)
                    compileInstruction(mv, instruction.second, stackMap, clean)
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
                    compileInstruction(mv, instruction.first, stackMap, clean)
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
                    compileInstruction(mv, instruction.second, stackMap, clean)
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
                    compileInstruction(mv, instruction.first, stackMap, clean)
                    //boxes the element if unboxed, and if it's a boxed-type or a complex type it does nothing
                    boxOrIgnore(mv, firstType)
                    compileInstruction(mv, instruction.second, stackMap, clean)
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
        else -> compileNumberComparison(mv, instruction.first, instruction.second, instruction.op, stackMap, clean)
    }
}

private fun compileNumberComparison(
    mv: MethodVisitor,
    first: TypedInstruction,
    second: TypedInstruction,
    op: CompareOp,
    stackMap: StackMap,
    clean: CleanupStack
) {
    val firstType = first.type
    val secondType = second.type
    assert(firstType.isNumType() && secondType.isNumType())
    val isIntCmp = firstType.isInt() && secondType.isInt()

    compileInstruction(mv, first, stackMap, clean)
    if (firstType.isBoxedPrimitive()) {
        stackMap.pop()
        stackMap.push(secondType.asUnboxed())
        unbox(mv, firstType)
    }
    if (firstType.isInt() && !isIntCmp) {
        mv.visitInsn(Opcodes.I2D)
    }
    compileInstruction(mv, second, stackMap, clean)
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

fun Type.asUnboxedOrIgnore() = runCatching { asUnboxed() }.getOrElse { this }

fun storeInstruction(type: Type) = when(type) {
    is Type.BoolT, Type.IntT -> Opcodes.ISTORE
    Type.DoubleT -> Opcodes.DSTORE
    is Type.JvmType, is Type.Lambda-> Opcodes.ASTORE
    Type.Nothing -> Opcodes.ASTORE
    Type.Null, is Type.JvmArray -> Opcodes.ASTORE
    is Type.Union ->Opcodes.ASTORE
    Type.Never, Type.UninitializedGeneric -> error("Cannot store variable of type never")
}

fun loadInstruction(type: Type) = when(type) {
    is Type.BoolT, Type.IntT -> Opcodes.ILOAD
    Type.DoubleT -> Opcodes.DLOAD
    is Type.JvmType, is Type.Lambda-> Opcodes.ALOAD
    Type.Nothing -> Opcodes.ALOAD
    Type.Null, is Type.JvmArray -> Opcodes.ALOAD
    is Type.Union ->Opcodes.ALOAD
    Type.Never, Type.UninitializedGeneric -> error("Cannot store variable of type never")
}

fun returnInstruction(type: Type) = when(type) {
    Type.Nothing -> Opcodes.RETURN
    Type.Never -> Opcodes.RETURN
    Type.IntT, is Type.BoolT -> Opcodes.IRETURN
    Type.DoubleT -> Opcodes.DRETURN
    else -> Opcodes.ARETURN

}

fun neverAssertException(mv: MethodVisitor) {
    mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
    mv.visitInsn(Opcodes.DUP)
    mv.visitLdcInsn("Unreachable case reached! This either results from a broken contract or a compiler error")
    mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "java/lang/IllegalStateException",
        "<init>",
        "(Ljava/lang/String;)V",
        false
    )
    mv.visitInsn(Opcodes.ATHROW)
}