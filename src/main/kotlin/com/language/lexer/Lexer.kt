package com.language.lexer

import com.language.CompareOp
import com.language.compilation.modifiers.Modifier

@JvmInline
value class MetaInfo(private val infos: Long) {

    constructor(start: Int, length: Int) : this((start.toLong() shl 32) or (length.toLong() and 0xFFFFFFFFL))

    val start get() = (infos shr 32).toInt()
    val length get() = (infos and 0xFFFFFFFFL).toInt()
}


data class PositionedToken<T : Token>(val token: T, val position: MetaInfo)

sealed interface Token {
    sealed interface KeyWord : Token
    data object If : KeyWord
    data object While : KeyWord
    data object Else : KeyWord
    data object Func : KeyWord
    data object Use : KeyWord
    data object Keep : KeyWord
    data object Struct : KeyWord
    data object Catch : KeyWord
    data object In : KeyWord, Identifier {
        override val name: String = "in"
    }
    data object Match : KeyWord
    data object Impl : KeyWord
    data object For : KeyWord
    data object Error : KeyWord, Identifier, ModifierToken {
        override val name: String = "error"
        override val modifier: Modifier = Modifier.Error
    }
    data object Public : KeyWord, Identifier, ModifierToken {
        override val name: String = "pub"
        override val modifier: Modifier = Modifier.Public
    }
    data object Inline : KeyWord, Identifier, ModifierToken {
        override val name: String = "inline"
        override val modifier: Modifier = Modifier.Inline
    }
    data object Self : KeyWord, Identifier {
        override val name: String = "self"
    }
    data object Type : KeyWord, Identifier {
        override val name: String = "type"
    }
    data object Return : KeyWord
    sealed interface ModifierToken : Token {
        val modifier: Modifier
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
    data object Null : Literal, KeyWord

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
    data object Pipe : Token
    data object QuestionMark : Token
    data object EqualSign : Token
    data object ExclamationMark : Token
    data object SemiColon : Token
    data object Collector : Token
    data object Range : Token
    data object Or : Token
    data object And : Token

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
        "_range" -> Token.Range
        "error" -> Token.Error
        "keep" -> Token.Keep
        "impl" -> Token.Impl
        "for" -> Token.For
        "in" -> Token.In
        "catch" -> Token.Catch
        "pub" -> Token.Public
        "null" -> Token.Null
        "inline" -> Token.Inline
        "return" -> Token.Return
        "_or" -> Token.Or
        "_and" -> Token.And
        "type" -> Token.Type
        else -> null
    }
}

private fun tryParseNumber(string: String): Token.ConstNum? {
    return string.toIntOrNull()?.let { Token.ConstNum(it) }
}

interface Index {
    val index: Int
}

private class CharIter(val array: CharArray) : Index {
    var idx = 0
    override val index: Int
        get() = idx
    fun nextChar() = array[idx++]
    fun currentChar() = array[idx - 1]
    fun previousChar() = array[(--idx) -1]

    fun hasNext() = idx < array.size


    fun info() = InfoBuilder(idx, this)
}


class InfoBuilder(private val start: Int, private val iter: Index) {
    fun finish() = MetaInfo(start, iter.index - 1 - start)
}

private fun singleCharMatches(iter: CharIter): Token? {
    return when (iter.nextChar()) {
        '*' -> Token.Star
        '-' -> Token.Minus
        ':' -> Token.Colon
        '+' -> Token.Plus
        ';' -> Token.SemiColon
        '?' -> Token.QuestionMark
        '|' -> Token.Pipe
        '/' -> {
            if (iter.nextChar() == '/') {
                while(iter.nextChar() != '\n') {}
                return null
            } else {
                iter.previousChar()
                Token.Slash
            }
        }
        '[' -> Token.OpenSquare
        ']' -> Token.ClosingSquare
        '(' -> Token.OpenBracket
        ')' -> Token.ClosingBracket
        '{' -> Token.OpenCurly
        '}' -> Token.ClosingCurly
        '!' -> Token.ExclamationMark
        '=' -> Token.EqualSign
        ',' -> Token.Comma
        '.' -> Token.Dot
        '<' -> Token.St
        '>' -> Token.Gt
        '"' -> {
            val stringContents = StringBuilder()
            while (true) {
                val char = iter.nextChar()
                if (char == '"') {
                    break
                }
                stringContents.append(char)
            }
            Token.ConstStr(stringContents.toString())
        }
        else -> null
    }
}

fun lexCode(code: String) = mutableListOf<PositionedToken<*>>().apply {
    val iter = CharIter(code
        .replace("==", " _eq ")
        .replace("<=", " _seq ")
        .replace(">=", " _geq ")
        .replace("!=", " _neq ")
        .replace("->", " _arrow ")
        .replace("...", " _collector ")
        .replace("..", " _range ")
        .replace("||", " _or ")
        .replace("&&", " _and ")
        .toCharArray())

    val buffer = StringBuilder()
    var keywordStart = iter.info()

    fun flush() {
        if (buffer.isEmpty()) {
            keywordStart = iter.info()
            return
        }
        val str = buffer.toString()
        val tk = tryFindKeyWord(str) ?: tryParseNumber(str) ?: Token.BasicIdentifier(str)
        add(PositionedToken(tk, keywordStart.finish()))
        buffer.clear()
        keywordStart = iter.info()
    }

    while (iter.hasNext()) {
        val info = iter.info()
        val tk = singleCharMatches(iter)
        if (tk != null) {
            flush()
            add(PositionedToken(tk, info.finish()))
            continue
        }
        if (iter.currentChar().isWhitespace()) {
            flush()
            continue
        }
        buffer.append(iter.currentChar())
    }

}
