// Copyright 2025 Daniel Hammerle
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.language.compilation

import com.language.*
import com.language.ArrayType
import com.language.Function
import com.language.compilation.metadata.LambdaAppender
import com.language.compilation.metadata.LambdaAppenderImpl
import com.language.compilation.modifiers.Modifier
import com.language.compilation.modifiers.Modifiers
import com.language.controlflow.MessageKind
import com.language.core.IntrinsicMemberFunctions
import com.language.lexer.MetaInfo
import org.apache.commons.codec.binary.Base32
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.exp

class CompilationException(
    val meta: MetaData,
    override val message: String,
    val kind: MessageKind = MessageKind.Logic
) : RuntimeException(message)

fun compile(module: ModuleLookup): IRModule {
    val functions: MutableMap<String, IRFunction> = mutableMapOf()
    val structs: MutableMap<String, IRStruct> = mutableMapOf()
    val implBlocks: MutableMap<TemplatedType, MutableSet<IRImpl>> = mutableMapOf()
    val appender = LambdaAppenderImpl(null)


    module.localSymbols.forEach { (name, entry) ->
        when (entry) {
            is Function -> functions[name] = compileFunction(function = entry, module, appender, emptySet())
            is Struct -> structs[name] = compileStruct(struct = entry, module)
            is Impl -> {
                val type =
                    if (entry.type is TemplatedType.Complex && module.hasLocalStruct(entry.type.signatureString)) {
                        TemplatedType.Complex(module.localName + entry.type.signatureString, entry.type.generics)
                    } else {
                        entry.type.populate(module)
                    }
                val block = compileImplBlock(entry, module, appender)
                implBlocks[type]?.add(block) ?: run { implBlocks[type] = mutableSetOf(block) }
            }
            is TypeDef -> {}  //ignore they don't exist after this stage
            is UseStatement -> {} //ignore they don't exist after this stage
            else -> throw CompilationException(
                entry.info.join(module.localName),
                "Invalid construct",
                MessageKind.Logic
            )
        }
    }

    return IRModule(module.localName, functions, structs, implBlocks).also { appender.module = it }
}

fun TemplatedType.populate(module: ModuleLookup): TemplatedType = when (this) {
    is TemplatedType.Complex -> {
        if (module.hasType(signatureString)) {
            module.unwindType(signatureString, this)
        } else {
            TemplatedType.Complex(
                populateSignature(signatureString, module),
                generics.map { it.populate(module) }
            )
        }
    }

    is TemplatedType.Union -> TemplatedType.Union(types.map { it.populate(module) }.toSet())
    else -> this
}

fun compileStruct(struct: Struct, module: ModuleLookup): IRStruct {
    val appendedGenerics = mutableMapOf<String, GenericType>()
    val fields = struct.args.map { (name, value) ->
        name to if (value == null) {
            val genericName = "__gen_$name"
            appendedGenerics[genericName] = GenericType(Modifiers.Empty, emptyList())
            TemplatedType.Generic(genericName)
        } else {
            value.populate(module)
        }
    }
    return IRStruct(fields, struct.generics + appendedGenerics, struct.modifiers, struct.info)
}

fun compileImplBlock(implBlock: Impl, module: ModuleLookup, lambdaAppender: LambdaAppender): IRImpl {
    val populatedType = if (implBlock.type is TemplatedType.Complex && module.hasLocalStruct(implBlock.type.signatureString)) {
        TemplatedType.Complex(module.localName + implBlock.type.signatureString, implBlock.type.generics)
    } else {
        implBlock.type.populate(module)
    }
    if (!populatedType.exists(module)) {
        throw CompilationException(
            implBlock.info.join(module.localName),
            "Invalid type $populatedType",
            MessageKind.Logic
        )
    }
    val fullImplSignature = module.localName + generateName()
    val implBlockLookup = module.withNewContainer(fullImplSignature)

    val methods =
        implBlock.methods.mapValues { (name, function) ->
            compileFunction(
                function,
                implBlockLookup,
                lambdaAppender,
                implBlock.generics.keys,
                if (function.modifiers.isModifier(Modifier.Intrinsic)) {
                    IntrinsicMemberFunctions[Triple(module.localName, populatedType, name)]!!
                } else {
                    null
                }
            )
        }
    val associatedFunctions = implBlock.associatedFunctions.mapValues { (_, function) ->

        compileFunction(
            function,
            implBlockLookup,
            lambdaAppender, implBlock.generics.keys,
        )
    }

    return IRImpl(fullImplSignature, methods, associatedFunctions, implBlock.generics)
}

fun generateName(): String = Base32().encodeToString(UUID.randomUUID().encodeUUID())
    .replace("=", "")


fun UUID.encodeUUID(): ByteArray = ByteBuffer.allocate(16).apply {
    putLong(mostSignificantBits)
    putLong(leastSignificantBits)
}.array()

fun TemplatedType.exists(module: ModuleLookup): Boolean = when (this) {
    is TemplatedType.Complex -> module.hasStruct(signatureString) || module.hasModule(signatureString)
    TemplatedType.IntT, TemplatedType.DoubleT, TemplatedType.BoolT -> true
    is TemplatedType.Generic -> true
    is TemplatedType.Nothing -> true
    is TemplatedType.Union -> types.all { it.exists(module) }
    TemplatedType.Null -> true
    is TemplatedType.Array -> itemType.exists(module)
    TemplatedType.Never -> true
}

fun compileFunction(function: Function, module: ModuleLookup, lambdaAppender: LambdaAppender, generics: Set<String>, externalBody: Instruction? = null): IRFunction {
    val body = externalBody ?: compileExpression(function.body, module, true, generics)

    return BasicIRFunction(
            function.args,
            function.returnType,
            function.generics,
            body,
            module.localImports.values.toSet() + module.localName,
            lambdaAppender,
            function.modifiers.isModifier(Modifier.Inline)
        )

}

fun compileStatements(statements: List<Statement>, module: ModuleLookup, uctl: Boolean, generics: Set<String>): List<Instruction> {
    return statements.map { compileStatement(it, module, uctl, generics) }
}

fun compileForLoopConstruct(construct: ForLoopConstruct, module: ModuleLookup, uctl: Boolean, generics: Set<String>): ForLoop {
    return ForLoop(
        compileExpression(construct.parent, module, uctl, generics),
        construct.name,
        construct.indexName,
        compileConstructingArg(construct.body, module, generics),
        construct.info.join(module.localName)
    )
}

fun compileStatement(statement: Statement, module: ModuleLookup, uctl: Boolean, generics: Set<String>): Instruction {
    return when (statement) {
        is Statement.Assign -> {
            val value = compileExpression(statement.value, module, uctl, generics)

            Instruction.StoreVar(
                statement.name,
                value = value,
                statement.info.join(module.localName)
            )
        }

        is Statement.For -> {
            Instruction.For(
                forLoop = compileForLoopConstruct(statement.forLoopConstruct, module, uctl, generics),
                statement.info.join(module.localName)
            )
        }

        is Statement.Expr -> compileExpression(expression = statement.expression, module, uctl, generics)
        is Statement.While -> Instruction.While(
            cond = compileExpression(statement.condition, module, uctl, generics),
            body = compileExpression(statement.body, module, false, generics),
            statement.info.join(module.localName)
        )

        is Statement.AssignProperty -> {
            Instruction.DynamicPropertyAssignment(
                parent = compileExpression(statement.parent, module, uctl, generics),
                name = statement.name,
                value = compileExpression(statement.value, module, uctl, generics),
                statement.info.join(module.localName)
            )
        }

        is Statement.Return -> Instruction.Return(
            compileExpression(
                statement.value ?: Expression.ReturningScope(emptyList(), MetaInfo(0)),
                module, uctl, generics
            ),
            statement.info.join(module.localName)
        )
    }
}

fun compileConstructingArg(arg: ConstructingArgument, module: ModuleLookup, generics: Set<String>): Instruction.ConstructingArgument {
    return when (arg) {
        is ConstructingArgument.Collect -> Instruction.ConstructingArgument.Collected(
            compileExpression(
                arg.expression,
                module,
                false, generics
            )
        )

        is ConstructingArgument.Normal -> Instruction.ConstructingArgument.Normal(
            compileExpression(
                arg.expression,
                module,
                false, generics
            )
        )

        is ConstructingArgument.ForLoop -> Instruction.ConstructingArgument.Iteration(
            compileForLoopConstruct(
                arg.forLoopConstruct,
                module,
                false, generics
            ),
        )
    }
}

fun compileExpression(expression: Expression, module: ModuleLookup, uctl: Boolean, generics: Set<String>): Instruction {
    return when (expression) {
        is Expression.ConstBool -> Instruction.LoadConstBool(expression.bool, expression.info.join(module.localName))
        is Expression.ConstNum -> if (expression.num == expression.num.toInt().toDouble())
            Instruction.LoadConstInt(expression.num.toInt(), expression.info.join(module.localName))
        else
            Instruction.LoadConstDouble(expression.num, expression.info.join(module.localName))

        is Expression.ConstStr -> Instruction.LoadConstString(expression.str, expression.info.join(module.localName))
        is Expression.IfElse -> Instruction.If(
            cond = compileExpression(expression.condition, module, uctl, generics),
            body = compileExpression(expression.body, module, false, generics),
            elseBody = expression.elseBody?.let { compileExpression(it, module, false, generics) },
            expression.info.join(module.localName)
        )

        is Expression.ConstNull -> Instruction.Null(expression.info.join(module.localName))
        is Expression.ConstArray -> {
            val items = expression.items.map { compileConstructingArg(it, module, generics) }
            when (expression.arrayType) {
                ArrayType.Implicit -> Instruction.ConstArray(
                    com.language.compilation.ArrayType.Object,
                    items,
                    expression.info.join(module.localName)
                )

                ArrayType.Int -> Instruction.ConstArray(
                    com.language.compilation.ArrayType.Int,
                    items,
                    expression.info.join(module.localName)
                )

                ArrayType.Double -> Instruction.ConstArray(
                    com.language.compilation.ArrayType.Double,
                    items,
                    expression.info.join(module.localName)
                )

                ArrayType.Bool -> Instruction.ConstArray(
                    com.language.compilation.ArrayType.Bool,
                    items,
                    expression.info.join(module.localName)
                )

                ArrayType.List -> Instruction.ConstArrayList(items, expression.info.join(module.localName))
            }
        }

        is Expression.Invoke -> compileInvoke(expression, module, uctl, generics)
        is Expression.Math -> Instruction.Math(
            op = expression.op,
            first = compileExpression(expression.first, module, uctl, generics),
            second = compileExpression(expression.second, module, uctl, generics),
            expression.info.join(module.localName)
        )

        is Expression.Match -> {
            val parent = compileExpression(expression.matchable, module, uctl, generics)
            val branches = expression.branches.map { (pattern, body) ->
                compilePattern(pattern, module, generics) to compileExpression(body, module, false, generics)
            }

            Instruction.Match(parent, branches, expression.info.join(module.localName))
        }


        is Expression.UnknownSymbol -> {
            //check if we have a generic in scope with the given name
            if (expression.name in generics) {
                return Instruction.GenericMention(expression.name, expression.info.join(module.localName))
            }
            throw CompilationException(
                expression.info.join(module.localName),
                "Unknown symbol `${expression.name}`"
            )
        }

        is Expression.VariableSymbol -> Instruction.LoadVar(expression.name, expression.info.join(module.localName))
        is Expression.ReturningScope -> Instruction.MultiInstructions(
            compileStatements(
                expression.expressions,
                module,
                uctl, generics
            ), expression.info.join(module.localName)
        )

        is Expression.AccessProperty -> {
            when (val parent = expression.parent) {
                //static property access
                is Expression.UnknownSymbol -> {
                    if(parent.sigName in generics) {
                        return Instruction.DynamicPropertyAccess(Instruction.GenericMention(parent.sigName, parent.info.join(module.localName)), expression.name, expression.info.join(module.localName))
                    }
                    val signature = populateSignature(SignatureString(parent.sigName), module)
                    if (!module.hasModule(signature)) {
                        println("[Warning] Unknown Module ${parent.name}")
                    }

                    Instruction.StaticPropertyAccess(signature, expression.name, expression.info.join(module.localName))
                }
                //dynamic property access
                else -> {
                    Instruction.DynamicPropertyAccess(
                        compileExpression(expression.parent, module, uctl, generics),
                        expression.name,
                        expression.info.join(module.localName)
                    )
                }
            }
        }

        is Expression.Comparing -> Instruction.Comparing(
            first = compileExpression(expression.first, module, uctl, generics),
            second = compileExpression(expression.second, module, uctl, generics),
            op = expression.op,
            expression.info.join(module.localName)
        )

        is Expression.Try -> Instruction.Try(
            compileExpression(expression.expression, module, uctl, generics),
            expression.info.join(module.localName)
        )

        is Expression.Catch -> Instruction.Catch(
            compileExpression(expression.expression, module, uctl, generics),
            expression.type?.populate(module),
            expression.info.join(module.localName)
        )

        is Expression.Keep -> {
            if (!uctl) throw CompilationException(
                expression.info.join(module.localName),
                "Keep expression must be within the unconditional top level scope of a function!"
            )

            //uctl is false
            // because keep is indeed conditional,
            // since it only runs the first time
            val value = compileExpression(expression.value, module, uctl = false, generics)

            Instruction.Keep(
                value,
                generateName().lowercase(),
                module.containerName,
                expression.info.join(module.localName)
            )
        }

        is Expression.Lambda -> {
            Instruction.Lambda(
                argNames = expression.args,
                body = compileExpression(expression.body, module, uctl = false, generics),
                capturedVariables = expression.capturedVariables.toList(),
                imports = module.localImports.values.toSet() + module.localName,
                expression.info.join(module.localName)
            )
        }

        is Expression.DefaultArray -> TODO()
        is Expression.CollectorArray -> TODO()
        is Expression.ArrayAccess -> TODO()

        is Range.FromLower -> TODO()
        is Range.Normal -> TODO()
        is Range.UntilUpper -> TODO()

        is Expression.BooleanOperation -> Instruction.LogicalOperation(
            compileExpression(expression.first, module, uctl, generics),
            compileExpression(
                expression.second,
                module,
                uctl = false, generics
            ), //unconditional top level is false because of possible short-circuiting
            expression.op,
            expression.info.join(module.localName)
        )

        is Expression.Not -> Instruction.Not(
            compileExpression(expression.expr, module, uctl, generics),
            expression.info.join(module.localName)
        )

        is Expression.Intrinsic -> error("This shouldnt be reached")
    }
}


fun compilePattern(pattern: Pattern, module: ModuleLookup, generics: Set<String>): IRPattern {
    return when (pattern) {
        is Pattern.Binding -> IRPattern.Binding(pattern.name)
        is Pattern.Conditional -> IRPattern.Condition(
            parent = compilePattern(pattern.parent, module, generics),
            condition = compileExpression(pattern.condition, module, false, generics)
        )

        is Pattern.Const -> IRPattern.Condition(
            parent = IRPattern.Binding("_"),
            condition = Instruction.Comparing(
                Instruction.LoadVar("_", pattern.info.join(module.localName)),
                compileExpression(pattern.value, module, false, generics),
                CompareOp.Eq,
                pattern.info.join(module.localName)
            )
        )

        is Pattern.Destructuring -> {
            when {
                pattern.type is TemplatedType.Complex && !module.hasStruct(populateSignature(pattern.type.signatureString, module)) -> IRPattern.Binding(
                    pattern.type.signatureString.oxideNotation
                )

                module.hasLocalStruct((pattern.type as TemplatedType.Complex).signatureString) -> {
                    IRPattern.Destructuring(
                        type = pattern.type.copy(signatureString = module.localName + pattern.type.signatureString),
                        patterns = pattern.patterns.map { compilePattern(it, module, generics) }
                    )
                }

                else -> {
                    IRPattern.Destructuring(
                        type = pattern.type.populate(module),
                        patterns = pattern.patterns.map { compilePattern(it, module, generics) }
                    )
                }
            }

        }
    }
}

fun compileInvoke(invoke: Expression.Invoke, module: ModuleLookup, uctl: Boolean, generics: Set<String>): Instruction {
    val args = invoke.args.values.map { compileExpression(it, module, uctl, generics) }
    return when (val parent = invoke.parent) {
        //dot call or module call
        is Expression.AccessProperty -> {
            when (val modName = parent.parent) {
                is Expression.UnknownSymbol -> {
                    //module call (for example, io.print("Hello World"))
                    if(modName.sigName in generics) {
                        return Instruction.DynamicCall(
                            Instruction.GenericMention(modName.sigName, parent.info.join(module.localName)),
                            parent.name,
                            args,
                            invoke.info.join(module.localName))
                    }
                    val signature = populateSignature(SignatureString(modName.sigName), module)
                    if (!module.hasModule(signature) && !module.hasStruct(signature)) {
                        throw CompilationException(
                            invoke.info.join(module.localName),
                            "No module with name ${modName.name}"
                        )
                    }
                    when {
                        module.nativeModule(signature) is Module -> {
                            Instruction.ModuleCall(
                                moduleName = signature,
                                name = parent.name,
                                args = args,
                                invoke.info.join(module.localName)
                            )
                        }

                        module.hasLocalStruct(signature) -> {
                            Instruction.ModuleCall(
                                moduleName = module.localName + signature,
                                name = parent.name,
                                args = args,
                                invoke.info.join(module.localName)
                            )
                        }

                        else -> Instruction.StaticCall(
                            classModuleName = signature,
                            name = parent.name,
                            args = args,
                            invoke.info.join(module.localName)
                        )
                    }

                }

                else -> {
                    //dot call
                    val instance = compileExpression(parent.parent, module, uctl, generics)
                    Instruction.DynamicCall(
                        parent = instance,
                        name = parent.name,
                        args = args,
                        invoke.info.join(module.localName)
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
                        invoke.info.join(module.localName)
                    )
                }

                module.hasLocalStruct(SignatureString(parent.sigName)) -> {
                    Instruction.ConstructorCall(
                        className = module.localName + parent.sigName,
                        args = args,
                        invoke.info.join(module.localName)
                    )
                }

                module.hasModule(fullName) || module.hasStruct(fullName) -> {
                    Instruction.ConstructorCall(
                        className = fullName,
                        args = args,
                        invoke.info.join(module.localName)
                    )
                }

                else -> throw CompilationException(
                    invoke.info.join(module.localName),
                    "Cannot invoke non function ${parent.name}"
                )
            }
        }
        //this calls a value invokable
        //meaning a value that has an invoke function like lambdas
        else -> {
            Instruction.InvokeLambda(
                compileExpression(parent, module, uctl, generics),
                args,
                invoke.info.join(module.localName)
            )
        }
    }
}

fun populateSignature(signatureString: SignatureString, module: ModuleLookup): SignatureString {
    if (module.hasStruct(signatureString)) {
        return module.localName + signatureString
    }
    if (module.localSymbols[signatureString.oxideNotation] is TypeDef) {
        return module.localName + signatureString
    }
    return when (val ctx = module.getImport(signatureString.members[0])) {
        is SignatureString -> signatureString.chopOfStart()?.let { ctx + it } ?: ctx
        else -> signatureString
    }
}