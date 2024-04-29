package com.language.parser

import com.language.*
import com.language.Function
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.compileExpression
import com.language.lexer.Token
import com.language.lexer.toCompareOp


typealias Tokens = ListIterator<Token>

fun parse(tokens: List<Token>): Module {
    val iter = tokens.listIterator()
    return parseFile(iter)
}

fun parseFile(tokens: Tokens): Module {
    val entries = mutableMapOf<String, ModuleChild>()
    while(tokens.hasNext()) {
        val (name, entry) = parseTopLevelEntity(tokens)
        entries[name] = entry
    }
    return Module(entries)
}

fun parseTopLevelEntity(tokens: Tokens): Pair<String, ModuleChild> {
    return when(val token = tokens.next()) {
        Token.Func -> {
            val name = tokens.expect<Token.Identifier>().name
            val args = if (tokens.visitNext() == Token.OpenBracket) {
                tokens.expect<Token.OpenBracket>()
                parseFunctionArgs(tokens)
            } else mutableListOf()
            val body = parseExpression(tokens, Variables.withEntries(args.toSet()))
            name to Function(args, body)
        }
        Token.Struct -> {
            val name = tokens.expect<Token.Identifier>().name
            val args = when(tokens.visitNext()) {
                Token.OpenCurly -> {
                    tokens.expect<Token.OpenCurly>()
                    parseTypedArgs(tokens)
                }
                else -> emptyMap()
            }
            name to Struct(args)
        }
        else -> error("Invalid token $token")
    }
}

fun parseTypedArgs(tokens: Tokens, closingSymbol: Token = Token.ClosingCurly): Map<String, String> {
    if (tokens.visitNext() == closingSymbol) {
        tokens.next()
        return emptyMap()
    }
    val entries = mutableMapOf<String, String>()
    while(true) {
        val name = tokens.expect<Token.Identifier>().name
        val type = tokens.expect<Token.Identifier>().name
        entries[name] = type
        when(tokens.next()) {
            closingSymbol -> return entries
            Token.Comma -> continue
            else -> error("Invalid token expected `,` or `$closingSymbol`")
        }
    }
}

fun parseFunctionArgs(tokens: Tokens): List<String> = mutableListOf<String>().apply {
    if (tokens.visitNext() == Token.ClosingBracket) {
        tokens.expect<Token.ClosingBracket>()
        return@apply
    }
    while (true) {
        add(tokens.expect<Token.Identifier>().name)
        when(val token = tokens.next()) {
            Token.Comma -> continue
            Token.ClosingBracket -> break
            else -> error("Invalid token $token (expected `,` or `)`")
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
    return when(tokens.next()) {
        is Token.While -> {
            val condition = parseExpression(tokens, variables)
            val body = parseExpression(tokens, variables)
            Statement.While(condition, body)
        }
        else -> {
            tokens.previous()
            val expr = parseExpression(tokens, variables)
            when {
                (expr is Expression.Invokable) && tokens.visitNext() == Token.EqualSign -> {
                    tokens.next()
                    Statement.Assign(expr.name, parseExpression(tokens, variables)).also {
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
    val first = parseExpressionAdd(tokens, variables)
    return when(tokens.visitNext()) {
        is Token.Eq -> {
            tokens.next()
            val other = parseExpressionComparing(tokens, variables)
            Expression.Comparing(first, other, CompareOp.Eq)
        }
        is Token.Neq -> {
            tokens.next()
            val other = parseExpressionComparing(tokens, variables)
            Expression.Comparing(first, other, CompareOp.Neq)
        }
        is Token.Gt -> {
            tokens.next()
            val other = parseExpressionComparing(tokens, variables)
            Expression.Comparing(first, other, CompareOp.Gt)
        }
        is Token.St -> {
            tokens.next()
            val other = parseExpressionComparing(tokens, variables)
            Expression.Comparing(first, other, CompareOp.St)
        }
        is Token.EGt -> {
            tokens.next()
            val other = parseExpressionComparing(tokens, variables)
            Expression.Comparing(first, other, CompareOp.EGt)
        }
        is Token.ESt -> {
            tokens.next()
            val other = parseExpressionComparing(tokens, variables)
            Expression.Comparing(first, other, CompareOp.ESt)
        }
        else -> first
    }
}


fun parseExpressionAdd(tokens: Tokens, variables: Variables): Expression {
    val first = parseExpressionMul(tokens, variables)
    return when(tokens.visitNext()) {
        is Token.Plus -> {
            tokens.next()
            val other = parseExpressionAdd(tokens, variables)
            Expression.Math(first, other, MathOp.Add)
        }
        is Token.Minus -> {
            tokens.next()
            val other = parseExpressionAdd(tokens, variables)
            Expression.Math(first, other, MathOp.Sub)
        }
        else -> first
    }
}

fun parseExpressionMul(tokens: Tokens, variables: Variables): Expression {
    val first = parseExpressionCall(tokens, variables)
    return when(tokens.visitNext()) {
        is Token.Star -> {
            tokens.next()
            val other = parseExpressionMul(tokens, variables)
            Expression.Math(first, other, MathOp.Mul)
        }
        is Token.Slash -> {
            tokens.next()
            val other = parseExpressionMul(tokens, variables)
            Expression.Math(first, other, MathOp.Div)
        }
        else -> first
    }
}

fun parseExpressionCall(tokens: Tokens, variables: Variables): Expression {
    val first = parseExpressionBase(tokens, variables)
    return parseExpressionCallBase(first, tokens, variables)
}

fun parseExpressionCallBase(first: Expression, tokens: Tokens, variables: Variables): Expression {
    return when(tokens.visitNext()) {
        is Token.OpenBracket -> {
            tokens.next()
            val args = parseCallingArgs(tokens, variables, Token.ClosingBracket)
            parseExpressionCallBase(
                Expression.Invoke(
                    first,
                    args.mapIndexed { index, expression -> "$index" to expression }.toMap()
                ),
                tokens,
                variables
            )
        }
        is Token.Dot -> {
            tokens.next()
            val name = tokens.expect<Token.Identifier>().name
            parseExpressionCallBase(
                Expression.AccessProperty(first, name),
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
            else -> error("Expected , or $closingSymbol but got $token")
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
    return when(tokens.visitNext()) {
        is Token.Match -> {
            tokens.expect<Token.Match>()
            parseMatch(tokens, variables)
        }
        is Token.Identifier -> {
            val token = tokens.expect<Token.Identifier>()

            if (token.name in arrayDeclarationTypes && tokens.visitNext() == Token.OpenSquare) {
                tokens.expect<Token.OpenSquare>()
                return parseArray(arrayDeclarationTypes[token.name]!!, tokens, variables)
            }

            if (token.name in variables)
                Expression.VariableSymbol(token.name)
            else
                Expression.UnknownSymbol(token.name)
        }
        is Token.OpenSquare -> {
            tokens.expect<Token.OpenSquare>()
            parseArray(ArrayType.Implicit, tokens, variables)
        }
        is Token.OpenCurly -> {
            tokens.expect<Token.OpenCurly>()
            val statements = parseStatements(tokens, variables.child())
            Expression.ReturningScope(statements)
        }
        is Token.If -> {
            tokens.expect<Token.If>()
            val condition = parseExpression(tokens, variables)
            val body = parseExpression(tokens, variables)
            val elseBody = if (tokens.visitNext() == Token.Else) {
                tokens.next()
                parseExpression(tokens, variables)
            } else null

            Expression.IfElse(condition, body, elseBody)
        }

        else -> parseConst(tokens)
    }
}

fun parseConstructingArgument(tokens: Tokens, variables: Variables): ConstructingArgument {
    return when(tokens.visitNext()) {
        is Token.Collector -> {
            tokens.expect<Token.Collector>()
            ConstructingArgument.Collect(parseExpression(tokens, variables))
        }
        else -> ConstructingArgument.Normal(parseExpression(tokens, variables))
    }
}

fun parseArray(type: ArrayType, tokens: Tokens, variables: Variables, closingSymbol: Token = Token.ClosingSquare): Expression.ConstArray {
    if (tokens.visitNext() == closingSymbol) {
        tokens.next()
        return Expression.ConstArray(type, emptyList())
    }

    val items = mutableListOf<ConstructingArgument>()
    while(true) {
        items += parseConstructingArgument(tokens, variables)
        when(tokens.next()) {
            Token.Comma -> continue
            closingSymbol -> break
            else -> error("Invalid symbol")
        }
    }

    return Expression.ConstArray(type, items)
}

fun parseConst(tokens: Tokens): Expression.Const {
    return when(val token = tokens.next()) {
        is Token.Minus -> {
            when (tokens.visitNext()) {
                is Token.ConstNum -> {
                    val number = tokens.expect<Token.ConstNum>()
                    Expression.ConstNum(-parseNumber(tokens, number.value).num)
                }
                else -> error("Unexpected token $token at parseExpressionBase")
            }
        }
        is Token.ConstStr -> Expression.ConstStr(token.value)
        is Token.ConstNum -> parseNumber(tokens, token.value)
        is Token.True -> Expression.ConstBool(true)
        is Token.False -> Expression.ConstBool(false)
        else -> error("Cannot")
    }
}


fun parseMatch(tokens: Tokens, variables: Variables): Expression.Match {
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

    return Expression.Match(parent, patterns)
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
            else -> error("Invalid token $tk")
        }
    }
}

private fun parsePattern(tokens: Tokens, variables: Variables): Pattern {
    val base = parsePatternBase(tokens, variables)
    return when(tokens.visitNext()) {
        Token.If -> {
            tokens.expect<Token.If>()

            val scope = variables.child().apply {
                base.bindingNames.forEach { name -> put(name) }
            }
            val condition = parseExpression(tokens, scope)
            Pattern.Conditional(condition, base)
        }
        is Token.Comparison -> {
            when(base) {
                is Pattern.Binding -> {
                    val op = tokens.expect<Token.Comparison>().toCompareOp()
                    val scope = variables.child().apply { put(base.name) }
                    val value = parseExpression(tokens, scope)
                    val condition = Expression.Comparing(Expression.VariableSymbol(base.name), value, op)
                    Pattern.Conditional(condition, base)
                }
                else -> base
            }
        }
        else -> base
    }
}

private fun parsePatternBase(tokens: Tokens, variables: Variables): Pattern {
    return when(tokens.visitNext()) {
        is Token.Identifier -> {
            val next = tokens.expect<Token.Identifier>().name
            runCatching { parseType(next) }.fold(
                onSuccess = { type ->
                    when(tokens.visitNext()) {
                        is Token.OpenBracket -> {
                            tokens.expect<Token.OpenBracket>()

                            val patterns = parsePatterns(tokens, variables)
                            Pattern.Destructuring(type, patterns)
                        }
                        else -> Pattern.Destructuring(type, emptyList())
                    }
                },
                onFailure = {
                    Pattern.Binding(next)
                }
            )
        }
        else -> {
            Pattern.Const(parseConst(tokens))
        }
    }

}

private fun parseType(string: String): Type = when(string) {
    "num" -> Type.IntT
    "str" -> Type.String
    "bool" -> Type.BoolT
    else -> {
        if (string.lowercase() == string) {
            error("")
        }
        Type.BasicJvmType(runCatching { SignatureString(string) }.getOrNull() ?: error("Invalid type $string"))
    }
}


private fun parseNumber(tokens: Tokens, initial: Int): Expression.ConstNum {
    return when(tokens.visitNext()) {
        is Token.Dot -> {
            tokens.next()
            val number = tokens.expect<Token.ConstNum>()
            Expression.ConstNum("${initial}.${number.value}".toDouble())
        }
        else -> Expression.ConstNum(initial.toDouble())
    }
}

fun Tokens.visitNext() = if (hasNext()) next().also { previous() } else null

inline fun<reified T: Token> Tokens.expect(): T = next() as T