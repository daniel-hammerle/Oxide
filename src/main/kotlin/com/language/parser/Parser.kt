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
package com.language.parser

import com.language.*
import com.language.Function
import com.language.compilation.GenericType
import com.language.compilation.SignatureString
import com.language.compilation.modifiers.Modifier
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.modifiers.modifiers
import com.language.controlflow.MessageKind
import com.language.lexer.*
import java.lang.RuntimeException
import java.util.UUID

class ParseException(message: String, val info: MetaInfo, val kind: MessageKind) : RuntimeException(message)

class Tokens(val tokens: List<PositionedToken<*>>) : Index {
    private var i = 0
    private var pos: MetaInfo = MetaInfo(0)

    override val index: Int
        get() = pos.start + pos.length
    fun hasNext() = i < tokens.size

    fun next(): Token = positionedNext().token

    fun positionedNext(): PositionedToken<*> {
        val tk = tokens[i++]
        pos = tk.position
        return tk
    }

    fun info() = InfoBuilder(pos.start + pos.length + 1, this)

    fun error(message: String, kind: MessageKind): Nothing {
        throw ParseException(message, pos, kind)
    }

    fun error(message: String, info: MetaInfo, kind: MessageKind): Nothing {
        throw ParseException(message, info, kind)
    }

    fun previous() = tokens[(--i) - 1].token

    fun visitNext() = tokens.getOrNull(i)?.token

    inline fun<reified T : Token> expect(): T {
        val meta = info()
        val item = next()
        if (item !is T) throw ParseException("Invalid token", meta.finish(), MessageKind.Syntax)
        return item
    }

    fun<T : Token> expect(token: T): T {
        val meta = info()
        val item = next()
        if (item != token)throw ParseException("Invalid token", meta.finish(), MessageKind.Syntax)
        return token
    }
}

fun parse(tokens: List<PositionedToken<*>>): Module {
    val iter = Tokens(tokens)
    return parseFile(iter)
}

fun parseFile(tokens: Tokens): Module {
    val entries = mutableMapOf<String, ModuleChild>()
    val implBlocks = mutableMapOf<TemplatedType, Impl>()
    val typeAliases = mutableMapOf<String, TypeDef>()
    val imports: MutableMap<String, SignatureString> = mutableMapOf()
    while(tokens.hasNext()) {
        val (name, entry) = parseTopLevelEntity(tokens)
        when(entry) {
            is Impl -> implBlocks[entry.type] = entry
            is UseStatement -> imports += entry.signatureStrings.map { it.structName to it }
            is TypeDef -> typeAliases[name] = entry
            else -> {}
        }
        entries[name] = entry

    }
    return Module(entries, implBlocks, imports, typeAliases)
}

fun parseModifiers(tokens: Tokens): Pair<Modifiers, MetaInfo> {
    val info = tokens.info()
    return modifiers {
        while(true) {
            when(tokens.visitNext()) {
                is Token.ModifierToken -> {
                    val tk = tokens.expect<Token.ModifierToken>()
                    setModifier(tk.modifier)
                }
                else -> break
            }
        }
    } to info.finish()
}



fun parseTopLevelEntity(tokens: Tokens): Pair<String, ModuleChild> {
    val info = tokens.info()
    val (modifiers, mInfo) = parseModifiers(tokens)
    return when(val token = tokens.next()) {
        Token.Func -> {
            if (!modifiers.isSubsetOf(Modifiers.FunctionModifiers)) {
                tokens.error("Invalid Modifiers cannot apply $modifiers to a function", mInfo, MessageKind.Logic)
            }
            val generics = parseGenericDefinition(tokens)
            val name = tokens.expect<Token.Identifier>().name
            val args = if (tokens.visitNext() == Token.OpenBracket) {
                tokens.expect<Token.OpenBracket>()
                parseFunctionArgs(tokens, closingSymbol = Token.ClosingBracket)
            } else mutableListOf()
            val returnType = if (tokens.visitNext() == Token.Arrow) {
                tokens.expect<Token.Arrow>()
                parseType(tokens)
            } else {
                null
            }
            val body = if (modifiers.isModifier(Modifier.Intrinsic)) {
                Expression.Intrinsic(name, info.finish())
            } else {
                parseExpression(tokens, BasicVariables.withEntries(args.map { it.first }.toSet()))
            }

            name to Function(args, returnType, generics, body, modifiers, info.finish())
        }
        Token.Type -> {
            val name = tokens.expect<Token.Identifier>().name
            val generics = parseGenericDefinition(tokens)

            tokens.expect<Token.EqualSign>()

            val type = parseType(tokens)

            name to TypeDef(generics, type, name, modifiers, info.finish())
        }
        Token.Struct -> {
            if (!modifiers.isSubsetOf(Modifiers.StructModifiers)) {
                tokens.error("Invalid Modifiers cannot apply $modifiers to a struct", mInfo, MessageKind.Logic)
            }
            val name = tokens.expect<Token.Identifier>().name
            val generics = parseGenericDefinition(tokens).toMap()
            val args = when(tokens.visitNext()) {
                Token.OpenCurly -> {
                    tokens.expect<Token.OpenCurly>()
                    parseTypedArgs(tokens, generics.keys)
                }
                else -> emptyList()
            }
            name to Struct(args, generics, modifiers, info.finish())
        }
        Token.Impl -> {
            if (!modifiers.isSubsetOf(Modifiers.ImplBlockModifiers)) {
                tokens.error("Invalid Modifiers cannot apply $modifiers to an impl block", mInfo, MessageKind.Logic)
            }
            val generics = parseGenericDefinition(tokens).toMap()
            val type = parseType(tokens, generics.keys)
            tokens.expect<Token.OpenCurly>()
            val entries: MutableMap<String, ModuleChild> = mutableMapOf()
            while(tokens.visitNext() != Token.ClosingCurly) {
                val entry = parseTopLevelEntity(tokens)
                entries += entry
            }
            tokens.expect<Token.ClosingCurly>()

            val functions = entries.mapValues { (name, entry) -> when(entry) {
                    is Function -> entry
                    else -> tokens.error("Invalid declaration of $name $entry inside an impl block", entry.info, MessageKind.Logic)
                }
            }
            val (methods, associatedFunctions) = functions.toList().partition { (_, function ) -> function.args.firstOrNull()?.first == "self" }

            UUID.randomUUID().toString() to Impl(type, methods.toMap(), associatedFunctions.toMap(), generics, modifiers, info.finish())
        }
        Token.Use -> UUID.randomUUID().toString() to UseStatement(parseImport(tokens), info.finish())
        else -> tokens.error("Invalid token $token", MessageKind.Syntax)
    }
}

fun parseImports(tokens: Tokens): Set<SignatureString> = mutableSetOf<SignatureString>().apply {
    while(true) {
        addAll(parseImport(tokens))
        when(tokens.next()) {
            is Token.Comma -> continue
            is Token.ClosingCurly -> break
            else -> tokens.error("Invalid token expected , or }", MessageKind.Syntax)
        }
    }
}

fun parseImport(tokens: Tokens): Set<SignatureString> {
    val (signature, flag) = parseSignature(tokens)
    return if (flag) {
        tokens.expect<Token.OpenCurly>()
        parseImports(tokens).map { signature + it }.toSet()
    } else {
        setOf(signature)
    }
}

fun parseTypedArgs(tokens: Tokens, generics: Set<String>, closingSymbol: Token = Token.ClosingCurly): List<Pair<String, TemplatedType?>> {
    if (tokens.visitNext() == closingSymbol) {
        tokens.next()
        return emptyList()
    }
    val entries = mutableListOf<Pair<String, TemplatedType?>>()
    while(true) {
        val name = tokens.expect<Token.Identifier>().name

        val type = if (tokens.visitNext() != closingSymbol && tokens.visitNext() !is Token.Comma) {
            parseType(tokens, generics)
        } else null
        entries += name to type
        when(tokens.next()) {
            closingSymbol -> return entries
            Token.Comma -> continue
            else -> tokens.error("Invalid token expected `,` or `$closingSymbol`", MessageKind.Syntax)
        }
    }
}

fun parseSignature(tokens: Tokens): Pair<SignatureString, Boolean> {
    val strings = mutableListOf<String>()
    var flag = false
    while(true) {
        if (strings.isNotEmpty() && tokens.visitNext() == Token.OpenCurly) {
            flag = true
            break
        }
        strings+=tokens.expect<Token.Identifier>().name
        when(tokens.visitNext()) {
            is Token.Colon -> {
                tokens.expect<Token.Colon>()
                tokens.expect<Token.Colon>()
                continue
            }
            else -> break
        }
    }
    return SignatureString(strings.joinToString("::")) to flag

}

fun parseFunctionArgs(tokens: Tokens, closingSymbol: Token): List<Pair<String, TemplatedType?>> = mutableListOf<Pair<String, TemplatedType?>>().apply {
    if (tokens.visitNext() == closingSymbol) {
        tokens.next()
        return@apply
    }
    while (true) {
        val name = tokens.expect<Token.Identifier>().name
        val type = if (tokens.visitNext() !in listOf(closingSymbol, Token.Comma)) {
            parseType(tokens)
        } else {
            null
        }
        add(name to type)
        when(val token = tokens.next()) {
            Token.Comma -> continue
            closingSymbol -> break
            else -> tokens.error("Invalid token $token (expected `,` or `$closingSymbol`", MessageKind.Syntax)
        }
    }
}

fun parseStatements(tokens: Tokens, variables: Variables, closingSymbol: Token = Token.ClosingCurly): List<Statement> = mutableListOf<Statement>().apply{
    while(tokens.visitNext() != closingSymbol) {
        add(parseStatement(tokens, variables))
    }
    tokens.next()
}

fun parseStatement(tokens: Tokens, variables: Variables): Statement {
    val info = tokens.info()
    return when(tokens.next()) {
        is Token.While -> {
            val condition = parseExpression(tokens, variables)
            val body = parseExpression(tokens, variables)
            Statement.While(condition, body, info.finish())
        }
        is Token.For -> {
            tokens.previous()
            Statement.For(parseForLoopConstruct(tokens, variables), info.finish())
        }
        is Token.Return -> {
            if (tokens.visitNext() == Token.ClosingCurly) {
                Statement.Return(null, info.finish())
            } else {
                val expr = parseExpression(tokens, variables)
                if (tokens.visitNext() != Token.ClosingCurly) {
                    val deadCodeBegin = tokens.info()
                    while(tokens.hasNext() && tokens.next() != Token.ClosingCurly) {}
                    tokens.error("Dead code detected", deadCodeBegin.finish(), MessageKind.Logic)
                }
                Statement.Return(expr, info.finish())
            }
        }
        else -> {
            tokens.previous()
            val expr = parseExpression(tokens, variables)
            when {
                expr is Expression.AccessProperty && tokens.visitNext() == Token.EqualSign ->  {
                    tokens.expect<Token.EqualSign>()
                    val value = parseExpression(tokens, variables)
                    Statement.AssignProperty(expr.parent, expr.name, value, info.finish())
                }
                (expr is Expression.Invokable) && tokens.visitNext() == Token.EqualSign -> {
                    tokens.next()
                    Statement.Assign(expr.name, parseExpression(tokens, variables), info.finish()).also {
                        variables.put(expr.name)
                    }
                }
                else -> Statement.Expr(expr)
            }
        }
    }
}

fun parseExpression(tokens: Tokens, variables: Variables): Expression {
    return parseExpressionComparing(tokens, variables)
}
fun parseExpressionComparing(tokens: Tokens, variables: Variables): Expression {
    val info = tokens.info()
    val first = parseExpressionAdd(tokens, variables)
    val op = when(tokens.visitNext()) {
        Token.Eq -> CompareOp.Eq
        Token.Neq -> CompareOp.Neq
        Token.Gt -> CompareOp.Gt
        Token.St -> CompareOp.St
        Token.EGt -> CompareOp.EGt
        Token.ESt -> CompareOp.ESt
        else -> return first
    }
    tokens.next()
    val other = parseExpressionComparing(tokens, variables)
    return Expression.Comparing(first, other, op, info.finish())
}


fun parseExpressionAdd(tokens: Tokens, variables: Variables): Expression {
    val info = tokens.info()
    val first = parseExpressionMul(tokens, variables)
    return when(tokens.visitNext()) {
        is Token.Plus -> {
            tokens.next()
            val other = parseExpressionAdd(tokens, variables)
            Expression.Math(first, other, MathOp.Add, info.finish())
        }
        is Token.Minus -> {
            tokens.next()
            val other = parseExpressionAdd(tokens, variables)
            Expression.Math(first, other, MathOp.Sub, info.finish())
        }
        else -> first
    }
}

fun parseExpressionMul(tokens: Tokens, variables: Variables): Expression {
    val info = tokens.info()
    val first = parseExpressionRanges(tokens, variables)
    return when(tokens.visitNext()) {
        is Token.Star -> {
            tokens.next()
            val other = parseExpressionMul(tokens, variables)
            Expression.Math(first, other, MathOp.Mul, info.finish())
        }
        is Token.Slash -> {
            tokens.next()
            val other = parseExpressionMul(tokens, variables)
            Expression.Math(first, other, MathOp.Div, info.finish())
        }
        is Token.Or -> {
            tokens.next()
            val other = parseExpressionMul(tokens, variables)
            Expression.BooleanOperation(first, other, BooleanOp.Or, info.finish())
        }
        is Token.And -> {
            tokens.next()
            val other = parseExpressionMul(tokens, variables)
            Expression.BooleanOperation(first, other, BooleanOp.And, info.finish())
        }
        else -> first
    }
}

fun parseExpressionRanges(tokens: Tokens, variables: Variables): Expression {
    val info = tokens.info()
    if (tokens.visitNext() == Token.Range) {
        tokens.expect<Token.Range>()
        val upperInclusive = if (tokens.visitNext() == Token.EqualSign) {
            tokens.expect<Token.EqualSign>()
            true
        } else {
            false
        }
        val bound = parseExpressionCall(tokens, variables)
        return Range.UntilUpper(bound, upperInclusive, info.finish())
    }

    val lower = parseExpressionCall(tokens, variables)
    if (tokens.visitNext() != Token.Range) return lower
    tokens.expect<Token.Range>()

    val upperInclusive = if (tokens.visitNext() == Token.EqualSign) {
        tokens.expect<Token.EqualSign>()
        true
    } else {
        false
    }

    val upper = parseExpressionCall(tokens, variables)
    return Range.Normal(lower, upper, upperInclusive, info.finish())
}

fun parseExpressionCall(tokens: Tokens, variables: Variables): Expression {
    val first = parseExpressionBase(tokens, variables)
    return parseExpressionCallBase(first, tokens, variables)
}

fun parseExpressionCallBase(first: Expression, tokens: Tokens, variables: Variables): Expression {
    val info = tokens.info()
    return when(tokens.visitNext()) {
        is Token.QuestionMark -> {
            tokens.next()
            parseExpressionCallBase(Expression.Try(first, info.finish()), tokens, variables)
        }
        is Token.OpenBracket -> {
            tokens.next()
            val args = parseCallingArgs(tokens, variables, Token.ClosingBracket) +
            if (tokens.visitNext() == Token.OpenCurly && tokens.visit2Next() == Token.Pipe) {
                val lambda = parseExpressionBase(tokens, variables)
                listOf(lambda)
            } else emptyList()

            parseExpressionCallBase(
                Expression.Invoke(
                    first,
                    args.mapIndexed { index, expression -> "$index" to expression }.toMap(),
                    info.finish()
                ),
                tokens,
                variables
            )
        }
        is Token.Dot -> {
            tokens.next()
            val name = tokens.expect<Token.Identifier>().name
            parseExpressionCallBase(
                Expression.AccessProperty(first, name, info.finish()),
                tokens,
                variables
            )
        }
        is Token.OpenCurly -> {
            if (tokens.visit2Next() != Token.Pipe) {
                return first
            }
            val lambda = parseExpressionBase(tokens, variables)
            val args = listOf(lambda)
            parseExpressionCallBase(
                Expression.Invoke(
                    first,
                    args.mapIndexed { index, expression -> "$index" to expression }.toMap(),
                    info.finish()
                ),
                tokens,
                variables
            )
        }
        else -> first
    }
}

fun parseCallingArgs(tokens: Tokens, variables: Variables, closingSymbol: Token): List<Expression> = mutableListOf<Expression>().apply {
    //return early in case its ()
    if (tokens.visitNext() == Token.ClosingBracket) {
        tokens.next()
        return@apply
    }
    while (true) {
        val arg = parseExpression(tokens, variables)
        add(arg)
        when (val token = tokens.next()) {
            closingSymbol -> break
            Token.Comma -> continue
            else -> tokens.error("Expected , or $closingSymbol but got $token", MessageKind.Syntax)
        }
    }
}

val arrayDeclarationTypes = mapOf(
    "int" to ArrayType.Int,
    "double" to ArrayType.Double,
    "bool" to ArrayType.Bool,
    "list" to ArrayType.List
)

fun parseExpressionBase(tokens: Tokens, variables: Variables): Expression {
    val info = tokens.info()
    return when(val tk = tokens.visitNext()) {
        is Token.Match -> {
            tokens.expect<Token.Match>()
            parseMatch(tokens, variables)
        }
        is Token.Identifier -> {

            if (tk.name in arrayDeclarationTypes && tokens.visit2Next() == Token.OpenSquare) {
                tokens.expect<Token.Identifier>()
                tokens.expect<Token.OpenSquare>()
                return parseArray(arrayDeclarationTypes[tk.name]!!, tokens, variables)
            }

            if (tk.name in variables) {
                tokens.expect<Token.Identifier>()
                Expression.VariableSymbol(tk.name, info.finish())
            } else {
                try {
                    Expression.UnknownSymbol(parseSignature(tokens).first.oxideNotation, info.finish())
                } catch (e: Exception) {
                    Expression.UnknownSymbol(tk.name, info.finish())
                }
            }
        }
        is Token.Catch -> parseCatch(tokens, variables)
        is Token.ExclamationMark -> {
            tokens.expect<Token.ExclamationMark>()
            Expression.Not(parseExpressionRanges(tokens, variables), info.finish())
        }
        is Token.Keep -> {
            tokens.expect<Token.Keep>()
            //the value that should be kept
            val value = parseExpression(tokens, variables)
            Expression.Keep(value, info.finish())
        }
        is Token.OpenSquare -> {
            tokens.expect<Token.OpenSquare>()
            parseArray(ArrayType.Implicit, tokens, variables)
        }
        is Token.OpenCurly -> {
            tokens.expect<Token.OpenCurly>()
            if (tokens.visitNext() == Token.Pipe) {
                tokens.expect<Token.Pipe>()
                val argumentNames = parseFunctionArgs(tokens, closingSymbol = Token.Pipe).map { it.first }
                val scope = variables.monitoredChild()
                argumentNames.forEach { scope.put(it) }

                val statements = parseStatements(tokens, scope)
                val capturedVariables = scope.usedParentVars()

                return Expression.Lambda(argumentNames, Expression.ReturningScope(statements, info.finish()), capturedVariables, info.finish())
            }
            val statements = parseStatements(tokens, variables.child())
            Expression.ReturningScope(statements, info.finish())
        }
        is Token.If -> {
            tokens.expect<Token.If>()
            val condition = parseExpression(tokens, variables)
            val body = parseExpression(tokens, variables)
            val elseBody = if (tokens.visitNext() == Token.Else) {
                tokens.next()
                parseExpression(tokens, variables)
            } else null

            Expression.IfElse(condition, body, elseBody, info.finish())
        }

        else -> parseConst(tokens)
    }
}

fun parseForLoopConstruct(tokens: Tokens, variables: Variables): ForLoopConstruct {
    val info = tokens.info()
    tokens.expect<Token.For>()
    val name = tokens.expect<Token.Identifier>().name
    val indexName = if (tokens.visitNext() == Token.Comma) {
        tokens.expect<Token.Comma>()
        tokens.expect<Token.Identifier>().name
    } else null
    tokens.expect<Token.In>()
    val parent = parseExpression(tokens, variables)
    val scope = variables.child()
    scope.put(name)
    indexName?.let { scope.put(it) }
    val body = parseConstructingArgument(tokens, scope)
    return ForLoopConstruct(parent, name, indexName, body, info.finish())
}

fun parseCatch(tokens: Tokens, variables: Variables): Expression.Catch {
    val info = tokens.info()
    tokens.expect<Token.Catch>()
    val type = if (tokens.visitNext() == Token.St) {
        parseType(tokens)
    } else {
        null
    }

    val expr = parseExpression(tokens, variables)
    val meta = info.finish()
    if (expr !is Expression.ReturningScope) {
        tokens.error("Expected block after catch", meta, MessageKind.Syntax)
    }

    return Expression.Catch(expr, type, meta)
}

fun parseConstructingArgument(tokens: Tokens, variables: Variables): ConstructingArgument {
    return when(tokens.visitNext()) {
        is Token.Collector -> {
            tokens.expect<Token.Collector>()
            ConstructingArgument.Collect(parseExpression(tokens, variables))
        }
        Token.For -> {
            ConstructingArgument.ForLoop(parseForLoopConstruct(tokens, variables))
        }
        else -> ConstructingArgument.Normal(parseExpression(tokens, variables))
    }
}

fun parseArray(type: ArrayType, tokens: Tokens, variables: Variables, closingSymbol: Token = Token.ClosingSquare): Expression.Array {
    val info = tokens.info()
    when (tokens.visitNext()) {
        closingSymbol -> {
            tokens.next()
            return Expression.ConstArray(type, emptyList(), info.finish())
        }
        else -> {}
    }


    val items = mutableListOf<ConstructingArgument>()
    while(true) {
        items += parseConstructingArgument(tokens, variables)
        when(val tk = tokens.next()) {

            Token.SemiColon -> {
                val item = when {
                    items.size != 1 -> tokens.error("", MessageKind.Syntax)
                    items[0] !is ConstructingArgument.Normal -> tokens.error("", MessageKind.Syntax)
                    else -> (items[0] as ConstructingArgument.Normal).expression
                }
                val size = parseExpression(tokens, variables)
                tokens.expect(closingSymbol)
                return Expression.DefaultArray(type, item, size, info.finish())
            }
            Token.Comma -> continue
            closingSymbol -> break
            else -> tokens.error("Expected `,` or `]` but got `$tk`", MessageKind.Syntax)
        }
    }

    return Expression.ConstArray(type, items, info.finish())
}

fun parseConst(tokens: Tokens): Expression.Const {
    val info = tokens.info()
    return when(val token = tokens.next()) {
        is Token.Minus -> {
            when (val num = tokens.next()) {
                is Token.ConstNum -> {
                    Expression.ConstNum(-parseNumber(tokens, num.value).num, info.finish())
                }
                else -> tokens.error("Unexpected token $num", MessageKind.Syntax)
            }
        }
        is Token.ConstStr -> Expression.ConstStr(token.value, info.finish())
        is Token.ConstNum -> parseNumber(tokens, token.value)
        is Token.True -> Expression.ConstBool(true, info.finish())
        is Token.False -> Expression.ConstBool(false, info.finish())
        is Token.Null -> Expression.ConstNull(info.finish())
        else -> tokens.error("Unexpected token $token", MessageKind.Syntax)
    }
}


fun parseMatch(tokens: Tokens, variables: Variables): Expression.Match {
    val info = tokens.info()
    val parent = parseExpression(tokens, variables)
    tokens.expect<Token.OpenCurly>()
    val patterns = mutableListOf<Pair<Pattern, Expression>>()
    while(true) {
        if (tokens.visitNext() == Token.ClosingCurly) {
            tokens.expect<Token.ClosingCurly>()
            break
        }

        val pattern = parsePattern(tokens, variables)
        tokens.expect<Token.Arrow>()
        val bodyScope = variables.child()
        pattern.bindingNames.forEach { bodyScope.put(it) }
        val body = parseExpression(tokens, bodyScope)

        patterns.add(pattern to body)
    }

    return Expression.Match(parent, patterns, info.finish())
}

private fun parsePatterns(tokens: Tokens, variables: Variables): List<Pattern> {
    if (tokens.visitNext() == Token.ClosingBracket) {
        tokens.expect<Token.ClosingBracket>()
        return emptyList()
    }
    val patterns = mutableListOf<Pattern>()
    while (true) {
        patterns += parsePattern(tokens, variables)
        when(val tk = tokens.next()) {
            Token.ClosingBracket -> return patterns
            Token.Comma -> continue
            else -> tokens.error("Invalid token $tk", MessageKind.Syntax)
        }
    }
}

private fun parsePattern(tokens: Tokens, variables: Variables): Pattern {
    val info = tokens.info()
    val base = parsePatternBase(tokens, variables)
    return when(tokens.visitNext()) {
        Token.If -> {
            tokens.expect<Token.If>()

            val scope = variables.child().apply {
                base.bindingNames.forEach { name -> put(name) }
            }
            val condition = parseExpression(tokens, scope)
            Pattern.Conditional(condition, base, info.finish())
        }
        is Token.Comparison -> {
            when(base) {
                is Pattern.Binding -> {
                    val op = tokens.expect<Token.Comparison>().toCompareOp()
                    val scope = variables.child().apply { put(base.name) }
                    val value = parseExpression(tokens, scope)
                    val condition = Expression.Comparing(Expression.VariableSymbol(base.name, info.finish()), value, op, info.finish())
                    Pattern.Conditional(condition, base, info.finish())
                }
                else -> base
            }
        }
        else -> base
    }
}

private fun parsePatternBase(tokens: Tokens, variables: Variables): Pattern {
    val info = tokens.info()
    val tk = tokens.visitNext()
    if (tk !is Token.Identifier) {
        return Pattern.Const(parseConst(tokens), info.finish())
    }

    if (tokens.visit2Next() is Token.Comparison) {
        tokens.expect<Token.Identifier>()
        val op = tokens.expect<Token.Comparison>()
        val expr = parseExpression(tokens, variables)
        return Pattern.Conditional(
            condition= Expression.Comparing(
                first =Expression.VariableSymbol(tk.name, info.finish()),
                second =expr,
                op = op.toCompareOp(),
                info.finish()
            ),
            parent = Pattern.Binding(tk.name, info.finish()),
            info.finish()
        )
    }

    return runCatching { parseType(tokens, allowGenerics = false) }.fold(
        onSuccess = { type ->
            when(tokens.visitNext()) {
                is Token.OpenBracket -> {
                    tokens.expect<Token.OpenBracket>()
                    val patterns = parsePatterns(tokens, variables)
                    Pattern.Destructuring(type, patterns, info.finish())
                }
                else -> Pattern.Destructuring(type, emptyList(), info.finish())
            }
        },
        onFailure = {
            Pattern.Binding(tk.name, info.finish())
        }
    )


}

private fun parseGenericDefinition(tokens: Tokens): List<Pair<String, GenericType>> {
    if (tokens.visitNext() != Token.St) {
        return emptyList()
    }
    tokens.expect<Token.St>()
    val generics = mutableListOf<Pair<String, GenericType>>()
    while(true) {
        val (modifiers, _) = parseModifiers(tokens)
        generics+=tokens.expect<Token.Identifier>().name to GenericType(modifiers, emptyList())
        when(val tk = tokens.next()) {
            is Token.Gt -> return generics
            is Token.Comma -> continue
            else -> tokens.error("Invalid token $tk expected closing `>` or `,`", MessageKind.Syntax)
        }
    }
}

private fun parseGenericArgs(tokens: Tokens, generics: Set<String>): List<TemplatedType> {
    if (tokens.visitNext() != Token.St) {
        return emptyList()
    }
    tokens.expect<Token.St>()
    val genericTypes = mutableListOf<TemplatedType>()
    while(true) {
        genericTypes+= parseType(tokens, generics)
        when(val tk = tokens.next()) {
            is Token.Gt -> return genericTypes
            is Token.Comma -> continue
            else -> tokens.error("Invalid token $tk expected closing `>` or `,`", MessageKind.Syntax)
        }
    }
}


private fun parseType(tokens: Tokens, generics: Set<String> = emptySet(), allowGenerics: Boolean = true): TemplatedType {
    val types = mutableSetOf<TemplatedType>()
    while(true) {
        val type = parseTypeBase(tokens, generics, allowGenerics)
        types.add(type)
        when(tokens.visitNext()) {
            is Token.Pipe -> {
                tokens.expect<Token.Pipe>()
            }
            else -> break
        }
    }
    return if (types.size == 1) {
        types.first()
    } else {
        TemplatedType.Union(types)
    }
}

private fun parseTypeBase(tokens: Tokens, generics: Set<String> = emptySet(), allowGenerics: Boolean = true): TemplatedType
= when(val tk = tokens.visitNext()) {
    is Token.Null -> {
        tokens.expect<Token.Null>()
        TemplatedType.Null
    }
    is Token.Identifier -> {
        when(tk.name) {
            in generics -> TemplatedType.Generic(tk.name).also { tokens.next() }
            "i32" ->  TemplatedType.IntT.also { tokens.next() }
            "f64" ->  TemplatedType.DoubleT.also { tokens.next() }
            "str" ->  TemplatedType.String.also { tokens.next() }
            "bool" -> TemplatedType.BoolT.also { tokens.next() }
            else -> {
                TemplatedType.Complex(
                    runCatching { parseSignature(tokens).first }.getOrNull() ?: tokens.error("Invalid type ${tk.name}", MessageKind.Syntax),
                    if (allowGenerics) parseGenericArgs(tokens, generics) else emptyList()
                )
            }
        }
    }
    is Token.OpenSquare -> {
        tokens.expect<Token.OpenSquare>()
        val item = parseType(tokens, generics)
        tokens.expect<Token.ClosingSquare>()
        TemplatedType.Array(item)
    }
    else -> {
        tokens.next()
        tokens.error("Unexpected token $tk", MessageKind.Syntax)
    }

}


private fun parseNumber(tokens: Tokens, initial: Int): Expression.ConstNum {
    val info = tokens.info()
    return when {
        tokens.visitNext() is Token.Dot && tokens.visit2Next() is Token.ConstNum -> {
            tokens.next()
            val number = tokens.expect<Token.ConstNum>()
            Expression.ConstNum("${initial}.${number.value}".toDouble(), info.finish())
        }
        else -> Expression.ConstNum(initial.toDouble(), info.finish())
    }
}

fun Tokens.visit2Next() = next().let { next().also { previous(); previous() } }



fun<A> Iterator<*>.expect(item: A) = when(val v = next()) {
    item -> item
    else -> error("Expected $item but got $v")
}