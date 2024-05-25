package com.language.parser

import com.language.*
import com.language.Function
import com.language.compilation.GenericType
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.modifiers.modifiers
import com.language.lexer.Token
import com.language.lexer.toCompareOp
import java.util.UUID


typealias Tokens = ListIterator<Token>

fun parse(tokens: List<Token>): Module {
    val iter = tokens.listIterator()
    return parseFile(iter)
}

fun parseFile(tokens: Tokens): Module {
    val entries = mutableMapOf<String, ModuleChild>()
    val implBlocks = mutableMapOf<TemplatedType, Impl>()
    val imports: MutableMap<String, SignatureString> = mutableMapOf()
    while(tokens.hasNext()) {
        val (name, entry) = parseTopLevelEntity(tokens)
        when(entry) {
            is Impl -> implBlocks[entry.type] = entry
            is UseStatement -> imports += entry.signatureStrings.map { it.structName to it }
            else -> {}
        }
        entries[name] = entry

    }
    return Module(entries, implBlocks, imports)
}

fun parseModifiers(tokens: Tokens): Modifiers = modifiers {
    while(true) {
        when(tokens.visitNext()) {
            is Token.ModifierToken -> {
                val tk = tokens.expect<Token.ModifierToken>()
                setModifier(tk.modifier)
            }
            else -> break
        }
    }
}



fun parseTopLevelEntity(tokens: Tokens): Pair<String, ModuleChild> {
    val modifiers = parseModifiers(tokens)
    return when(val token = tokens.next()) {
        Token.Func -> {
            if (!modifiers.isSubsetOf(Modifiers.FunctionModifiers)) {
                error("Invalid Modifiers cannot apply $modifiers to a function")
            }
            val name = tokens.expect<Token.Identifier>().name
            val args = if (tokens.visitNext() == Token.OpenBracket) {
                tokens.expect<Token.OpenBracket>()
                parseFunctionArgs(tokens, closingSymbol = Token.ClosingBracket)
            } else mutableListOf()
            val body = parseExpression(tokens, BasicVariables.withEntries(args.toSet()))
            name to Function(args, body, modifiers)
        }
        Token.Struct -> {
            if (!modifiers.isSubsetOf(Modifiers.StructModifiers)) {
                error("Invalid Modifiers cannot apply $modifiers to a struct")
            }
            val name = tokens.expect<Token.Identifier>().name
            val generics = parseGenericDefinition(tokens)
            val args = when(tokens.visitNext()) {
                Token.OpenCurly -> {
                    tokens.expect<Token.OpenCurly>()
                    parseTypedArgs(tokens, generics.keys)
                }
                else -> emptyMap()
            }
            name to Struct(args, generics, modifiers)
        }
        Token.Impl -> {
            if (!modifiers.isSubsetOf(Modifiers.ImplBlockModifiers)) {
                error("Invalid Modifiers cannot apply $modifiers to an impl block")
            }
            val generics = parseGenericDefinition(tokens)
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
                    else -> error("Invalid declaration of $name $entry insisde an impl block")
                }
            }
            val (methods, associatedFunctions) = functions.toList().partition { (_, function ) -> function.args.firstOrNull() == "self" }

            UUID.randomUUID().toString() to Impl(type, methods.toMap(), associatedFunctions.toMap(), generics, modifiers)
        }
        Token.Use -> UUID.randomUUID().toString() to UseStatement(parseImport(tokens))
        else -> error("Invalid token $token")
    }
}

fun parseImports(tokens: Tokens): Set<SignatureString> = mutableSetOf<SignatureString>().apply {
    while(true) {
        addAll(parseImport(tokens))
        when(tokens.next()) {
            is Token.Comma -> continue
            is Token.ClosingCurly -> break
            else -> error("Invalid token expected , or }")
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

fun parseTypedArgs(tokens: Tokens, generics: Set<String>, closingSymbol: Token = Token.ClosingCurly): Map<String, TemplatedType> {
    if (tokens.visitNext() == closingSymbol) {
        tokens.next()
        return emptyMap()
    }
    val entries = mutableMapOf<String, TemplatedType>()
    while(true) {
        val name = tokens.expect<Token.Identifier>().name
        val type = parseType(tokens, generics)
        entries[name] = type
        when(tokens.next()) {
            closingSymbol -> return entries
            Token.Comma -> continue
            else -> error("Invalid token expected `,` or `$closingSymbol`")
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

fun parseFunctionArgs(tokens: Tokens, closingSymbol: Token): List<String> = mutableListOf<String>().apply {
    if (tokens.visitNext() == closingSymbol) {
        tokens.next()
        return@apply
    }
    while (true) {
        add(tokens.expect<Token.Identifier>().name)
        when(val token = tokens.next()) {
            Token.Comma -> continue
            closingSymbol -> break
            else -> error("Invalid token $token (expected `,` or `$closingSymbol`")
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
        is Token.For -> {
            val name = tokens.expect<Token.Identifier>().name
            tokens.expect<Token.In>()
            val scope = variables.child()
            scope.put(name)
            val parent = parseExpression(tokens, variables)
            val body = parseExpression(tokens, scope)

            Statement.For(parent, name, body)
        }
        else -> {
            tokens.previous()
            val expr = parseExpression(tokens, variables)
            when {
                expr is Expression.AccessProperty && tokens.visitNext() == Token.EqualSign ->  {
                    tokens.expect<Token.EqualSign>()
                    val value = parseExpression(tokens, variables)
                    Statement.AssignProperty(expr.parent, expr.name, value)
                }
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
        is Token.QuestionMark -> {
            tokens.next()
            parseExpressionCallBase(Expression.Try(first), tokens, variables)
        }
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
                Expression.VariableSymbol(tk.name)
            } else {
                try {
                    Expression.UnknownSymbol(parseSignature(tokens).first.oxideNotation)
                } catch (e: Exception) {
                    Expression.UnknownSymbol(tk.name)
                }
            }
        }
        is Token.Keep -> {
            tokens.expect<Token.Keep>()
            //the value that should be kept
            val value = parseExpression(tokens, variables)
            Expression.Keep(value)
        }
        is Token.OpenSquare -> {
            tokens.expect<Token.OpenSquare>()
            parseArray(ArrayType.Implicit, tokens, variables)
        }
        is Token.OpenCurly -> {
            tokens.expect<Token.OpenCurly>()
            if (tokens.visitNext() == Token.Pipe) {
                tokens.expect<Token.Pipe>()
                val argumentNames = parseFunctionArgs(tokens, closingSymbol = Token.Pipe)
                val scope = variables.monitoredChild()
                argumentNames.forEach { scope.put(it) }

                val statements = parseStatements(tokens, scope)
                val capturedVariables = scope.usedParentVars()

                return Expression.Lambda(argumentNames, Expression.ReturningScope(statements), capturedVariables)
            }
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
        is Token.Null -> Expression.ConstNull
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
    return when(val tk = tokens.visitNext()) {
        is Token.Identifier -> {
            runCatching { parseType(tokens, allowGenerics = false) }.fold(
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
                    Pattern.Binding(tk.name)
                }
            )
        }
        else -> {
            Pattern.Const(parseConst(tokens))
        }
    }

}

private fun parseGenericDefinition(tokens: Tokens): Map<String, GenericType> {
    if (tokens.visitNext() != Token.St) {
        return emptyMap()
    }
    tokens.expect<Token.St>()
    val generics = mutableMapOf<String, GenericType>()
    while(true) {
        val modifiers = parseModifiers(tokens)
        generics+=tokens.expect<Token.Identifier>().name to GenericType(modifiers, emptyList())
        when(val tk = tokens.next()) {
            is Token.Gt -> return generics
            is Token.Comma -> continue
            else -> error("Invalid token $tk expected closing `>` or `,`")
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
            else -> error("Invalid token $tk expected closing `>` or `,`")
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

private fun parseTypeBase(tokens: Tokens, generics: Set<String> = emptySet(), allowGenerics: Boolean = true): TemplatedType = when(val tk = tokens.visitNext()) {
    is Token.Null -> {
        tokens.expect<Token.Null>()
        TemplatedType.Null
    }
    is Token.Identifier -> {
        when(tk.name) {
            in generics -> TemplatedType.Generic(tk.name).also { tokens.next() }
            "int" -> TemplatedType.IntT.also { tokens.next() }
            "double" ->  TemplatedType.DoubleT.also { tokens.next() }
            "str" ->  TemplatedType.String.also { tokens.next() }
            "bool" ->  TemplatedType.BoolT.also { tokens.next() }
            else -> {
                TemplatedType.Complex(
                    runCatching { parseSignature(tokens).first }.getOrNull() ?: error("Invalid type ${tk.name}"),
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
    else -> error("Unexpected token $tk")

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

fun Tokens.visit2Next() = next().let { next().also { previous(); previous() } }

inline fun<reified A> Iterator<*>.expect(): A = when (val v = next()){
    !is A -> error("Expected ${A::class.simpleName} but got `$v`")
    else -> v
}