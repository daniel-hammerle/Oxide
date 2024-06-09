package com.language.compilation

import com.language.*
import com.language.ArrayType
import com.language.Function
import com.language.codegen.compileInstruction
import com.language.compilation.metadata.LambdaAppender
import com.language.compilation.metadata.LambdaAppenderImpl
import com.language.parser.parseExpression
import org.apache.commons.codec.binary.Base32
import org.apache.commons.codec.binary.Base64
import java.nio.ByteBuffer
import java.util.*

fun compile(module: ModuleLookup): IRModule {
    val functions: MutableMap<String, IRFunction> = mutableMapOf()
    val structs: MutableMap<String, IRStruct> = mutableMapOf()
    val implBlocks: MutableMap<TemplatedType, MutableSet<IRImpl>> = mutableMapOf()
    val appender = LambdaAppenderImpl(null)


    module.localSymbols.forEach { (name, entry) ->
        when(entry) {
            is Function -> functions[name] = compileFunction(function = entry, module, appender)
            is Struct -> structs[name] = compileStruct(struct = entry, module)
            is Impl -> {
                val type = if (entry.type is TemplatedType.Complex && module.hasLocalStruct(entry.type.signatureString)) {
                    TemplatedType.Complex(module.localName + entry.type.signatureString, entry.type.generics)
                } else {
                    entry.type.populate(module)
                }
                val block = compileImplBlock(entry, module, appender)
                implBlocks[type]?.add(block) ?: run { implBlocks[type] = mutableSetOf(block) }
            }
            is UseStatement -> {}
            else -> error("Invalid construct")
        }
    }

    return IRModule(module.localName, functions, structs, implBlocks).also { appender.module = it }
}

fun TemplatedType.populate(module: ModuleLookup): TemplatedType = when(this) {
    is TemplatedType.Complex -> TemplatedType.Complex(populateSignature(signatureString, module), generics)
    else -> this
}

fun compileStruct(struct: Struct, module: ModuleLookup): IRStruct {
    val fields = struct.args.mapValues { it.value.populate(module) }
    return IRStruct(fields, struct.generics, struct.modifiers)
}

fun compileImplBlock(implBlock: Impl, module: ModuleLookup, lambdaAppender: LambdaAppender): IRImpl {
    if (!implBlock.type.exists(module)) {
        error("Invalid type ${implBlock.type}")
    }
    val fullImplSignature = module.localName +  generateName()
    val implBlockLookup = module.withNewContainer(fullImplSignature)

    val methods = implBlock.methods.mapValues { (_, function) -> compileFunction(function, implBlockLookup, lambdaAppender) }
    val associatedFunctions = implBlock.associatedFunctions.mapValues { (_, function) -> compileFunction(function, implBlockLookup, lambdaAppender) }

    return IRImpl(fullImplSignature, methods, associatedFunctions, implBlock.generics)
}

fun generateName(): String = Base32().encodeToString(UUID.randomUUID().encodeUUID())
    .replace("=", "")


fun UUID.encodeUUID(): ByteArray = ByteBuffer.allocate(16).apply {
    putLong(mostSignificantBits)
    putLong(leastSignificantBits)
}.array()

fun TemplatedType.exists(module: ModuleLookup): Boolean = when(this) {
    is TemplatedType.Complex -> module.hasStruct(populateSignature(signatureString, module)) || module.hasModule(populateSignature(signatureString, module))
    TemplatedType.IntT, TemplatedType.DoubleT, TemplatedType.BoolT -> true
    is TemplatedType.Generic -> true
    is TemplatedType.Nothing -> true
    is TemplatedType.Union -> types.all { it.exists(module) }
    TemplatedType.Null -> true
    is TemplatedType.Array -> itemType.exists(module)
}

fun compileFunction(function: Function, module: ModuleLookup, lambdaAppender: LambdaAppender): IRFunction {

    val body = compileExpression(function.body, module, true)

    return IRFunction(
        function.args,
        body,
        module.localImports.values.toSet() + module.localName,
        lambdaAppender
    )
}

fun compileStatements(statements: List<Statement>, module: ModuleLookup, uctl: Boolean): List<Instruction> {
    return statements.mapIndexed { i, it -> compileStatement(it, module, uctl) }
}

fun compileForLoopConstruct(construct: ForLoopConstruct, module: ModuleLookup, uctl: Boolean): ForLoop {
    return ForLoop(
        compileExpression(construct.parent, module, uctl),
        construct.name,
        construct.indexName,
        compileConstructingArg(construct.body, module)
    )
}

fun compileStatement(statement: Statement, module: ModuleLookup, uctl: Boolean): Instruction {
    return when(statement) {
        is Statement.Assign -> {
            val value = compileExpression(statement.value, module, uctl)

            Instruction.StoreVar(
                statement.name,
                value = value
            )
        }
        is Statement.For -> {
            Instruction.For(
                forLoop = compileForLoopConstruct(statement.forLoopConstruct, module, uctl)
            )
        }
        is Statement.Expr -> compileExpression(expression = statement.expression, module, uctl)
        is Statement.While -> Instruction.While(
            cond = compileExpression(statement.condition, module, uctl),
            body = compileExpression(statement.body, module, false)
        )

        is Statement.AssignProperty -> {
            Instruction.DynamicPropertyAssignment(
                parent = compileExpression(statement.parent, module, uctl),
                name = statement.name,
                value = compileExpression(statement.value, module, uctl)
            )
        }
    }
}

fun compileConstructingArg(arg: ConstructingArgument, module: ModuleLookup): Instruction.ConstructingArgument {
    return when(arg) {
        is ConstructingArgument.Collect -> Instruction.ConstructingArgument.Collected(compileExpression(arg.expression, module, false))
        is ConstructingArgument.Normal -> Instruction.ConstructingArgument.Normal(compileExpression(arg.expression, module, false))
        is ConstructingArgument.ForLoop -> Instruction.ConstructingArgument.Iteration(compileForLoopConstruct(arg.forLoopConstruct, module, false), )
    }
}

fun compileExpression(expression: Expression, module: ModuleLookup, uctl: Boolean): Instruction {
    return when(expression) {
        is Expression.ConstBool -> Instruction.LoadConstBool(expression.bool)
        is Expression.ConstNum -> if (expression.num == expression.num.toInt().toDouble())
            Instruction.LoadConstInt(expression.num.toInt())
        else
            Instruction.LoadConstDouble(expression.num)
        is Expression.ConstStr -> Instruction.LoadConstString(expression.str)
        is Expression.IfElse -> Instruction.If(
            cond = compileExpression(expression.condition, module, uctl),
            body = compileExpression(expression.body, module, false),
            elseBody = expression.elseBody?.let { compileExpression(it, module, false) }
        )
        is Expression.ConstNull -> Instruction.Null
        is Expression.ConstArray -> {
            val items = expression.items.map { compileConstructingArg(it, module) }
            when(expression.arrayType) {
                ArrayType.Implicit -> Instruction.ConstArray(com.language.compilation.ArrayType.Object, items)
                ArrayType.Int -> Instruction.ConstArray(com.language.compilation.ArrayType.Int, items)
                ArrayType.Double -> Instruction.ConstArray(com.language.compilation.ArrayType.Double, items)
                ArrayType.Bool -> Instruction.ConstArray(com.language.compilation.ArrayType.Bool, items)
                ArrayType.List -> Instruction.ConstArrayList(items)
            }
        }
        is Expression.Invoke -> compileInvoke(expression, module, uctl)
        is Expression.Math -> Instruction.Math(
            op = expression.op,
            first = compileExpression(expression.first, module, uctl),
            second = compileExpression(expression.second, module, uctl)
        )
        is Expression.Match -> {
            val parent = compileExpression(expression.matchable, module, uctl)
            val branches = expression.branches.map { (pattern, body) ->
                compilePattern(pattern, module) to compileExpression(body, module, false)
            }

            Instruction.Match(parent, branches)
        }
        is Expression.UnknownSymbol -> error("Unknown symbol `${expression.name}`")
        is Expression.VariableSymbol -> Instruction.LoadVar(expression.name)
        is Expression.ReturningScope -> Instruction.MultiInstructions(compileStatements(expression.expressions, module, uctl))
        is Expression.AccessProperty -> {
            when(val parent = expression.parent) {
                //static property access
                is Expression.UnknownSymbol -> {
                    val signature = populateSignature(SignatureString(parent.sigName), module)
                    if (!module.hasModule(signature)) {
                        println("[Warning] Unknown Module ${parent.name}")
                    }

                    Instruction.StaticPropertyAccess(signature, expression.name)
                }
                //dynamic property access
                else -> {
                    Instruction.DynamicPropertyAccess(compileExpression(expression.parent, module, uctl), expression.name)
                }
            }
        }

        is Expression.Comparing -> Instruction.Comparing(
            first = compileExpression(expression.first, module, uctl),
            second = compileExpression(expression.second, module, uctl),
            op = expression.op
        )

        is Expression.Try -> Instruction.Try(compileExpression(expression.expression, module, uctl))
        is Expression.Keep -> {
            if (!uctl) error("Keep expression must be within the unconditional top level scope of a function!")

            //uctl is false
            // because keep is indeed conditional,
            // since it only runs the first time
            val value = compileExpression(expression.value, module, uctl = false)

            Instruction.Keep(value, generateName().lowercase(), module.containerName)
        }

        is Expression.Lambda -> {
            Instruction.Lambda(
                argNames = expression.args,
                body = compileExpression(expression.body, module, uctl = false),
                capturedVariables = expression.capturedVariables.toList(),
                imports = module.localImports.values.toSet()
            )
        }

        is Expression.DefaultArray -> TODO()
        is Expression.CollectorArray -> TODO()
    }
}


fun compilePattern(pattern: Pattern, module: ModuleLookup): IRPattern {
    return when(pattern) {
        is Pattern.Binding -> IRPattern.Binding(pattern.name)
        is Pattern.Conditional -> IRPattern.Condition(
            parent = compilePattern(pattern.parent, module),
            condition = compileExpression(pattern.condition, module, false)
        )
        is Pattern.Const -> IRPattern.Condition(
            parent = IRPattern.Binding("_"),
            condition = Instruction.Comparing(Instruction.LoadVar("_"), compileExpression(pattern.value, module, false), CompareOp.Eq)
        )
        is Pattern.Destructuring -> {
            when {
                pattern.type is TemplatedType.Complex && !module.hasStruct(pattern.type.signatureString) -> IRPattern.Binding(pattern.type.signatureString.oxideNotation)
                module.hasLocalStruct((pattern.type as TemplatedType.Complex).signatureString) -> {
                    IRPattern.Destructuring(
                        type = pattern.type.copy(signatureString = module.localName + pattern.type.signatureString),
                        patterns = pattern.patterns.map { compilePattern(it, module) }
                    )
                }

                else -> {
                    IRPattern.Destructuring(
                        type = pattern.type.populate(module),
                        patterns = pattern.patterns.map { compilePattern(it, module) }
                    )
                }
            }

        }
    }
}

fun compileInvoke(invoke: Expression.Invoke, module: ModuleLookup, uctl: Boolean): Instruction {
    val args =  invoke.args.values.map { compileExpression(it, module, uctl) }
    return when(val parent = invoke.parent) {
        //dot call or module call
        is Expression.AccessProperty -> {
            when(val modName = parent.parent) {
                is Expression.UnknownSymbol -> {
                    //module call (for example, io.print("Hello World"))
                    val signature = populateSignature(SignatureString(modName.sigName), module)
                    if (!module.hasModule(signature) && !module.hasStruct(signature)) {
                        error("No module with name ${modName.name}")
                    }
                    when {
                        module.nativeModule(signature) is Module -> {
                            Instruction.ModuleCall(
                                moduleName = signature,
                                name = parent.name,
                                args = args,
                            )
                        }
                        module.hasLocalStruct(signature) -> {
                            Instruction.ModuleCall(
                                moduleName = module.localName + signature,
                                name = parent.name,
                                args = args,
                            )
                        }
                        else -> Instruction.StaticCall(
                            classModuleName = signature,
                            name = parent.name,
                            args = args,
                        )
                    }

                }
                else -> {
                    //dot call
                    val instance = compileExpression(parent.parent, module, uctl)
                    Instruction.DynamicCall(
                        parent = instance,
                        name = parent.name,
                        args = args,
                    )
            }


            }

        }
        //call in the same module
        // foo()
        is Expression.UnknownSymbol -> {
            val fullName = populateSignature(SignatureString(parent.name), module)
            when {
                module.localGetFunction(parent.name) is Function -> {
                    Instruction.ModuleCall(
                        moduleName = module.localName,
                        name = parent.name,
                        args = args,
                    )
                }
                module.hasLocalStruct(SignatureString(parent.sigName)) -> {
                    Instruction.ConstructorCall(
                        className = module.localName +parent.sigName,
                        args = args
                    )
                }
                module.hasModule(fullName) || module.hasStruct(fullName) -> {
                    Instruction.ConstructorCall(
                        className = fullName,
                        args = args
                    )
                }
                else -> error("Cannot invoke non funciton ${parent.name}")
            }
        }
        //this calls a value invokable
        //meaning a value that has an invoke function like lambdas
        else -> {
            Instruction.InvokeLambda(
                compileExpression(parent, module, uctl),
                args
            )
        }
    }
}

fun populateSignature(signatureString: SignatureString, module: ModuleLookup): SignatureString {
    return when (val ctx = module.getImport(signatureString.members[0])) {
        is SignatureString -> signatureString.chopOfStart()?.let { ctx + it } ?: ctx
        else -> signatureString
    }
}