package com.language.lookup.jvm.contract

import com.language.parser.expect

enum class Token {
    True,
    False,
    UnderScore,
    Null,
    NotNull,
    Arrow,
    Comma,
    Fail,
    SemiColon
}

class ContractLexerIterator(private val baseString: String) {
    private var index = 0

    fun skip(n: Int) {
        index+=n
    }

    fun hasNext() = index <= baseString.length

    fun next() = baseString[index++]
}

fun lexContractString(contractString: String): List<Token> = mutableListOf<Token>().apply {
    val iter = ContractLexerIterator(contractString)
    while (iter.hasNext()) {
        //since true, false, null, !null, _, and -> start with different characters,
        //we always only care about the first character and skip the rest
        //(except for fail and false where we need the 3rd char as well
        when(val c = iter.next()) {
            't' -> {
                iter.skip(3)
                add(Token.True)
            }
            '_' -> add(Token.UnderScore)
            ',' -> add(Token.Comma)
            '-' -> {
                iter.skip(1)
                add(Token.Arrow)
            }
            'n'  -> {
                iter.skip(3)
                add(Token.Null)
            }
            '!' -> {
                iter.skip(4)
                add(Token.NotNull)
            }
            ';' -> add(Token.SemiColon)
            'f' -> {
                iter.skip(1)
                when (iter.next()) {
                    'l' -> add(Token.False)
                    'i' -> add(Token.Fail)
                    else -> error("Invalid character expected `fail` or `false`")
                }
            }
            else -> {
                if (c.isWhitespace()) continue
                error("Invalid Character `$c`")
            }
        }
    }
}

typealias Tokens = ListIterator<Token>

enum class ContractItem {
    True,
    False,
    Null,
    NotNull,
    Ignore,
    Fail
}

data class ContractVariant(val arguments: List<ContractItem>, val returnType: ContractItem)

fun Tokens.skipIfTrue(token: Token) = (next() == token).also { if (!it) previous() }

fun parseContract(tokens: Tokens): Set<ContractVariant> {
    val variants = mutableSetOf<ContractVariant>()
    while(true) {
        variants += parseContractVariant(tokens)
        if (tokens.hasNext()) {
            assert(tokens.next() == Token.SemiColon)
        } else break
    }
    return variants
}

fun parseContractVariant(tokens: Tokens): ContractVariant {
    val condition = parseContractCondition(tokens)
    val returnValue = parseContractItem(tokens)
    return ContractVariant(condition, returnValue)
}

fun parseContractCondition(tokens: Tokens): List<ContractItem> {
    if (tokens.skipIfTrue(Token.Arrow)) return emptyList()

    val arguments = mutableListOf<ContractItem>()
    while (true) {
        arguments += parseContractItem(tokens)
        when(val tk = tokens.next()) {
            Token.Comma -> continue
            Token.Arrow -> break
            else -> error("Invalid token expected `,` or `->` but got $tk")
        }
    }
    //arrow token
    tokens.next()
    return arguments
}

fun parseContractItem(tokens: Tokens)= when(val tk = tokens.next()) {
    Token.True -> ContractItem.True
    Token.False -> ContractItem.False
    Token.UnderScore -> ContractItem.Ignore
    Token.Null -> ContractItem.Null
    Token.NotNull -> ContractItem.NotNull
    Token.Fail -> ContractItem.Fail
    else -> error("Invalid token while parsing contract item: `$tk`")
}

fun parseContractString(contractString: String): Set<ContractVariant> {
    if (contractString.isEmpty()) return emptySet()
    return parseContract(lexContractString(contractString).listIterator())
}
