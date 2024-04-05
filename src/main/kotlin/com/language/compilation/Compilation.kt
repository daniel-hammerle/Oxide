package com.language.compilation

import com.language.*
import com.language.Function

fun compile(module: ModuleLookup): IRModule {
    val functions: MutableMap<String, IRFunction> = mutableMapOf()
    val structs: MutableMap<String, IRStruct> = mutableMapOf()
    module.localSymbols.forEach { (name, entry) ->
        when(entry) {
            is Function -> functions[name] = compileFunction(function = entry, module)
            is Struct -> structs[name] = compileStruct(struct = entry, module)
            else -> error("Invalid construct")
        }
    }

    return IRModule(module.localName, functions, structs)
}

fun compileStruct(struct: Struct, module: ModuleLookup): IRStruct {
    val fields = struct.args.mapValues { it.value.parseType(module) }
    return IRStruct(fields)
}

fun String.parseType(module: ModuleLookup) = when(this) {
    "num" -> Type.DoubleT
    "str" -> Type.String
    "bool" -> Type.BoolT
    else -> {
        when {
            module.hasModule(SignatureString(this)) -> Type.JvmType(SignatureString(this))
            else -> error("Invalid type $this")
        }
    }
}

fun compileFunction(function: Function, module: ModuleLookup): IRFunction {

    val body = compileExpression(function.body, module)

    return IRFunction(
        function.args,
        body
    )
}

fun compileStatements(statements: List<Statement>, module: ModuleLookup): List<Instruction> {
    return statements.mapIndexed { i, it -> compileStatement(it, module) }
}

fun compileStatement(statement: Statement, module: ModuleLookup): Instruction {
    return when(statement) {
        is Statement.Assign -> {
            val value = compileExpression(statement.value, module)
            Instruction.MultiInstructions(
                mutableListOf<Instruction>(
                    Instruction.StoreVar(
                        statement.name,
                        value = value
                    )
                )
            )
        }
        is Statement.Expr -> compileExpression(expression = statement.expression, module)
        is Statement.While -> Instruction.While(
            cond = compileExpression(statement.condition, module),
            body = compileExpression(statement.body, module)
        )
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
        is Expression.Invoke -> compileInvoke(expression, module)
        is Expression.Math -> Instruction.Math(
            op = expression.op,
            first = compileExpression(expression.first, module),
            second = compileExpression(expression.second, module)
        )
        is Expression.UnknownSymbol -> error("Unknown symbol `${expression.name}`")
        is Expression.VariableSymbol -> Instruction.LoadVar(expression.name)
        is Expression.ReturningScope -> Instruction.MultiInstructions(compileStatements(expression.expressions, module))
        is Expression.AccessProperty -> {
            when(val parent = expression.parent) {
                //static property access
                is Expression.UnknownSymbol -> {
                    if (!module.hasModule(SignatureString(parent.name))) {
                        println("[Warning] Unknown Module ${parent.name}")
                    }

                    Instruction.StaticPropertyAccess(SignatureString(parent.name), expression.name)
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

fun compileInvoke(invoke: Expression.Invoke, module: ModuleLookup): Instruction {
    val args =  invoke.args.values.map { compileExpression(it, module) }
    return when(val parent = invoke.parent) {
        //dot call or module call
        is Expression.AccessProperty -> {
            when(val modName = parent.parent) {
                is Expression.UnknownSymbol -> {
                    //module call (for example, io.print("Hello World"))
                    if (!module.hasModule(SignatureString(modName.name))) {
                        error("No module with name ${modName.name}")
                    }
                    when(module.nativeModule(SignatureString(modName.name))) {
                        is Module -> {
                            Instruction.ModuleCall(
                                moduleName = SignatureString(modName.name),
                                name = parent.name,
                                args = args,
                            )
                        }
                        else -> Instruction.StaticCall(
                            classModuleName = SignatureString(modName.name),
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
                module.hasLocalStruct(SignatureString(parent.name)) -> {
                    Instruction.ConstructorCall(
                        className = module.localName + parent.name,
                        args = args
                    )
                }
                module.hasModule(SignatureString(parent.name)) || module.hasStruct(SignatureString(parent.name)) -> {
                    Instruction.ConstructorCall(
                        className = SignatureString(parent.name),
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