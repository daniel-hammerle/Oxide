package com.language.lexer

import com.language.CompareOp

sealed interface Token {
    sealed interface KeyWord : Token
    data object If : KeyWord
    data object While : KeyWord
    data object Else : KeyWord
    data object Func : KeyWord
    data object Use : KeyWord
    data object Struct : KeyWord
    data object Match : KeyWord
    data object Impl : KeyWord
    data object Error : KeyWord, Identifier {
        override val name: String = "error"
    }
    data object Self : KeyWord, Identifier {
        override val name: String = "self"
    }

    data object Colon : Token


    sealed interface Identifier : Token {
        val name: String
    }

    data class BasicIdentifier(override val name: String) : Identifier

    sealed interface Literal : Token
    data class ConstStr(val value: String) : Literal
    data class ConstNum(val value: Int) : Literal
    data object True : Literal, KeyWord
    data object False : Literal, KeyWord

    data object OpenSquare : Token
    data object ClosingSquare : Token

    data object OpenBracket : Token
    data object ClosingBracket : Token
    data object Comma : Token
    data object OpenCurly : Token
    data object Dot : Token
    data object ClosingCurly : Token
    data object Plus : Token
    data object Minus : Token
    data object Star : Token
    data object Slash : Token
    data object EqualSign : Token
    data object ExclamationMark : Token

    data object Collector : Token

    data object Arrow : Token

    sealed interface Comparison : Token
    data object Eq : Comparison
    data object Neq : Comparison
    data object St : Comparison
    data object ESt : Comparison
    data object Gt : Comparison
    data object EGt : Comparison
}

fun Token.Comparison.toCompareOp(): CompareOp = when(this) {
    Token.EGt -> CompareOp.EGt
    Token.ESt -> CompareOp.ESt
    Token.Eq -> CompareOp.Eq
    Token.Gt -> CompareOp.Gt
    Token.Neq ->CompareOp.Neq
    Token.St -> CompareOp.St
}

private fun tryFindKeyWord(string: String): Token? {
    return when (string) {
        "if" -> Token.If
        "else" -> Token.Else
        "while" -> Token.While
        "func" -> Token.Func
        "true" -> Token.True
        "self" -> Token.Self
        "false" -> Token.False
        "struct" -> Token.Struct
        "use" -> Token.Use
        "match" -> Token.Match
        "_eq" -> Token.Eq
        "_neq" -> Token.Neq
        "_geq" -> Token.EGt
        "_seq" -> Token.ESt
        "_arrow" -> Token.Arrow
        "_collector" -> Token.Collector
        "error" -> Token.Error
        "impl" -> Token.Impl
        else -> null
    }
}

private fun tryParseNumber(string: String): Token.ConstNum? {
    return string.toIntOrNull()?.let { Token.ConstNum(it) }
}

fun lexCode(code: String) = mutableListOf<Token>().apply {

    var buffer: StringBuilder = StringBuilder()

    fun flush() {
        if (buffer.isEmpty()) {
            return
        }
        val string = buffer.toString()
        val keyword = tryFindKeyWord(string)
        val token = keyword ?: tryParseNumber(string) ?: Token.BasicIdentifier(string)
        this.add(token)
        buffer = StringBuilder()
    }

    val iter = code
        .replace("==", " _eq ")
        .replace("<=", " _seq ")
        .replace(">=", " _geq ")
        .replace("!=", " _neq ")
        .replace("->", " _arrow ")
        .replace("...", " _collector ")
        .toCharArray().iterator()
    while (iter.hasNext()) {
        val token = when (val c = iter.nextChar()) {
            '*' -> {
                flush()
                Token.Star
            }
            '-' -> {
                flush()
                Token.Minus
            }
            ':' -> {
                flush()
                Token.Colon
            }
            '+' -> {
                flush()
                Token.Plus
            }
            '/' -> {
                flush()
                Token.Slash
            }
            '[' -> {
                flush()
                Token.OpenSquare
            }
            ']' -> {
                flush()
                Token.ClosingSquare
            }
            '(' -> {
                flush()
                Token.OpenBracket
            }
            ')' -> {
                flush()
                Token.ClosingBracket
            }
            '{' -> {
                flush()
                Token.OpenCurly
            }
            '}' -> {
                flush()
                Token.ClosingCurly
            }
            '!' -> {
                flush()
                Token.ExclamationMark
            }
            '=' -> {
                flush()
                Token.EqualSign
            }
            ',' -> {
                flush()
                Token.Comma
            }
            '.' -> {
                flush()
                Token.Dot
            }
            '<' -> {
                flush()
                Token.St
            }
            '>' -> {
                flush()
                Token.Gt
            }
            '"' -> {
                val stringContents = StringBuilder()
                while(true) {
                    val char = iter.nextChar()
                    if (char == '"') {
                        break
                    }
                    stringContents.append(char)
                }
                Token.ConstStr(stringContents.toString())
            }
            else -> {
                if (c.isWhitespace()) {
                    flush()
                    continue
                }
                buffer.append(c)
                continue
            }
        }
        this.add(token)
    }

}