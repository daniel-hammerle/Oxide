package com.language.parser

import com.language.*
import com.language.Function
import com.language.lexer.Token


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
            val args = parseCallingArgs(tokens, variables)
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

fun parseCallingArgs(tokens: Tokens, variables: Variables): List<Expression> = mutableListOf<Expression>().apply {
    //return early in case its ()
    if (tokens.visitNext() == Token.ClosingBracket) {
        tokens.next()
        return@apply
    }
    while (true) {
        val arg = parseExpression(tokens, variables)
        add(arg)
        when (val token = tokens.next()) {
            Token.ClosingBracket -> break
            Token.Comma -> continue
            else -> error("Expected , or ) but got $token")
        }
    }
}

fun parseExpressionBase(tokens: Tokens, variables: Variables): Expression {
    return when(val token = tokens.next()) {
        is Token.ConstStr -> Expression.ConstStr(token.value)
        is Token.ConstNum -> parseNumber(tokens, token.value)
        is Token.True -> Expression.ConstBool(true)
        is Token.False -> Expression.ConstBool(false)
        is Token.Identifier -> if (token.name in variables)
            Expression.VariableSymbol(token.name)
        else
            Expression.UnknownSymbol(token.name)
        is Token.OpenCurly -> {
            val statements = parseStatements(tokens, variables.child())
            Expression.ReturningScope(statements)
        }
        is Token.If -> {
            val condition = parseExpression(tokens, variables)
            val body = parseExpression(tokens, variables)
            val elseBody = if (tokens.visitNext() == Token.Else) {
                tokens.next()
                parseExpression(tokens, variables)
            } else null

            Expression.IfElse(condition, body, elseBody)
        }
        is Token.Minus -> {
            when (tokens.visitNext()) {
                is Token.ConstNum -> {
                    val number = tokens.expect<Token.ConstNum>()
                    Expression.ConstNum(-parseNumber(tokens, number.value).num)
                }
                else -> error("Unexpected token $token at parseExpressionBase")
            }
        }
        else -> error("Unexpected token $token at parseExpressionBase")
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