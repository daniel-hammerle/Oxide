package com.language.compilation

import com.language.*
import com.language.ArrayType
import com.language.Function
import com.language.codegen.compileInstruction
import com.language.parser.parseExpression
import org.apache.commons.codec.binary.Base32
import org.apache.commons.codec.binary.Base64
import java.nio.ByteBuffer
import java.util.*

fun compile(module: ModuleLookup): IRModule {
    val functions: MutableMap<String, IRFunction> = mutableMapOf()
    val structs: MutableMap<String, IRStruct> = mutableMapOf()
    val implBlocks: MutableMap<TemplatedType, IRImpl> = mutableMapOf()
    module.localSymbols.forEach { (name, entry) ->
        when(entry) {
            is Function -> functions[name] = compileFunction(function = entry, module)
            is Struct -> structs[name] = compileStruct(struct = entry, module)
            is Impl -> {
                val type = if (entry.type is TemplatedType.Complex && module.hasLocalStruct(entry.type.signatureString)) {
                    TemplatedType.Complex(module.localName + entry.type.signatureString, entry.type.generics)
                } else {
                    entry.type.populate(module)
                }
                implBlocks[type] = compileImplBlock(entry, module)
            }
            is UseStatement -> {}
            else -> error("Invalid construct")
        }
    }

    return IRModule(module.localName, functions, structs, implBlocks)
}

fun TemplatedType.populate(module: ModuleLookup): TemplatedType = when(this) {
    is TemplatedType.Complex -> TemplatedType.Complex(populateSignature(signatureString, module), generics)
    else -> this
}

fun compileStruct(struct: Struct, module: ModuleLookup): IRStruct {
    val fields = struct.args.mapValues { it.value.populate(module) }
    return IRStruct(fields, struct.generics)
}

fun compileImplBlock(implBlock: Impl, module: ModuleLookup): IRImpl {
    if (!implBlock.type.exists(module)) {
        error("Invalid type ${implBlock.type}")
    }

    val methods = implBlock.methods.mapValues { (_, function) -> compileFunction(function, module) }
    val associatedFunctions = implBlock.associatedFunctions.mapValues { (_, function) -> compileFunction(function, module) }

    return IRImpl(module.localName +  generateName(), methods, associatedFunctions)
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
    is TemplatedType.Array -> itemType.exists(module)
}

fun compileFunction(function: Function, module: ModuleLookup): IRFunction {

    val body = compileExpression(function.body, module)

    return IRFunction(
        function.args,
        body,
        module.localImports.values.toSet() + module.localName
    )
}

fun compileStatements(statements: List<Statement>, module: ModuleLookup): List<Instruction> {
    return statements.mapIndexed { i, it -> compileStatement(it, module) }
}

fun compileStatement(statement: Statement, module: ModuleLookup): Instruction {
    return when(statement) {
        is Statement.Assign -> {
            val value = compileExpression(statement.value, module)

            Instruction.StoreVar(
                statement.name,
                value = value
            )
        }
        is Statement.For -> {
            Instruction.For(
                parent = compileExpression(statement.parent, module),
                name = statement.itemName,
                body = compileExpression(statement.body, module)
            )
        }
        is Statement.Expr -> compileExpression(expression = statement.expression, module)
        is Statement.While -> Instruction.While(
            cond = compileExpression(statement.condition, module),
            body = compileExpression(statement.body, module)
        )
    }
}

fun compileConstructingArg(arg: ConstructingArgument, module: ModuleLookup): Instruction.ConstructingArgument {
    return when(arg) {
        is ConstructingArgument.Collect -> Instruction.ConstructingArgument.Collected(compileExpression(arg.expression, module))
        is ConstructingArgument.Normal -> Instruction.ConstructingArgument.Normal(compileExpression(arg.expression, module))
    }
}

fun compileExpression(expression: Expression, module: ModuleLookup): Instruction {
    return when(expression) {
        is Expression.ConstBool -> Instruction.LoadConstBool(expression.bool)
        is Expression.ConstNum -> if (expression.num == expression.num.toInt().toDouble())
            Instruction.LoadConstInt(expression.num.toInt())
        else
            Instruction.LoadConstDouble(expression.num)
        is Expression.ConstStr -> Instruction.LoadConstString(expression.str)
        is Expression.IfElse -> Instruction.If(
            cond = compileExpression(expression.condition, module),
            body = compileExpression(expression.body, module),
            elseBody = expression.elseBody?.let { compileExpression(it, module) }
        )
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
        is Expression.Invoke -> compileInvoke(expression, module)
        is Expression.Math -> Instruction.Math(
            op = expression.op,
            first = compileExpression(expression.first, module),
            second = compileExpression(expression.second, module)
        )
        is Expression.Match -> {
            val parent = compileExpression(expression.matchable, module)
            val branches = expression.branches.map { (pattern, body) ->
                compilePattern(pattern, module) to compileExpression(body, module)
            }

            Instruction.Match(parent, branches)
        }
        is Expression.UnknownSymbol -> error("Unknown symbol `${expression.name}`")
        is Expression.VariableSymbol -> Instruction.LoadVar(expression.name)
        is Expression.ReturningScope -> Instruction.MultiInstructions(compileStatements(expression.expressions, module))
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
                    Instruction.DynamicPropertyAccess(compileExpression(expression.parent, module), expression.name)
                }
            }
        }

        is Expression.Comparing -> Instruction.Comparing(
            first = compileExpression(expression.first, module),
            second = compileExpression(expression.second, module),
            op = expression.op
        )
    }
}


fun compilePattern(pattern: Pattern, module: ModuleLookup): IRPattern {
    return when(pattern) {
        is Pattern.Binding -> IRPattern.Binding(pattern.name)
        is Pattern.Conditional -> IRPattern.Condition(
            parent = compilePattern(pattern.parent, module),
            condition = compileExpression(pattern.condition, module)
        )
        is Pattern.Const -> IRPattern.Condition(
            parent = IRPattern.Binding("_"),
            condition = Instruction.Comparing(Instruction.LoadVar("_"), compileExpression(pattern.value, module), CompareOp.Eq)
        )
        is Pattern.Destructuring -> {


            when {
                pattern.type is Type.JvmType && !module.hasStruct(pattern.type.signature) ->
                    error("Invalid type ${pattern.type} for destructuring (either JVM Type or non existent)")

                module.hasLocalStruct((pattern.type as Type.BasicJvmType).signature) -> {
                    IRPattern.Destructuring(
                        type = pattern.type.copy(signature = module.localName + pattern.type.signature),
                        patterns = pattern.patterns.map { compilePattern(it, module) }
                    )
                }
                else -> {
                    IRPattern.Destructuring(
                        type = pattern.type,
                        patterns = pattern.patterns.map { compilePattern(it, module) }
                    )
                }
            }

        }
    }
}

fun compileInvoke(invoke: Expression.Invoke, module: ModuleLookup): Instruction {
    val args =  invoke.args.values.map { compileExpression(it, module) }
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
                    val instance = compileExpression(parent.parent, module)
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
                module.hasModule(SignatureString(parent.sigName)) || module.hasStruct(SignatureString(parent.sigName)) -> {
                    Instruction.ConstructorCall(
                        className = SignatureString(parent.sigName),
                        args = args
                    )
                }
                else -> error("Cannot invoke non funciton ${parent.name}")
            }
        }
        //this would be a value invokable
        //meaning a value that has an invoke function
        //you can still call .invoke for now but simply calling it will work too in the future
        else -> error("Invoking dynamic value invokables not implemented yet")
    }
}

fun populateSignature(signatureString: SignatureString, module: ModuleLookup): SignatureString {
    return when (val ctx = module.getImport(signatureString.members[0])) {
        is SignatureString -> signatureString.chopOfStart()?.let { ctx + it } ?: ctx
        else -> signatureString
    }
}