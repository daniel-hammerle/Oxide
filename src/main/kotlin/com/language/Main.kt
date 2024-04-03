package com.language

import com.language.codegen.compileCheckedFunction
import com.language.codegen.compileProject
import com.language.compilation.*
import com.language.lexer.lexCode
import com.language.parser.parse
import java.io.File

fun main() {

    val tokens = lexCode(File("language.lang").readText())
    val module = parse(tokens)

    val runtimeLib = "./RuntimeLib/build/libs/RuntimeLib-1.0-SNAPSHOT.jar"
    val extensionClassLoader = ExtensionClassLoader(runtimeLib)
    val modules = extensionClassLoader.createModuleTree()
    val lookup = BasicModuleLookup(module, "main", modules, ClassLoader.getPlatformClassLoader())


    val result = compile(lookup)
    val irLookup =  BasicIRModuleLookup(setOf(result), extensionClassLoader)
    val type = result.functions["main"]!!.type(listOf(), irLookup)
    println(type)

    val bytes = compileProject(irLookup)["main"]!!
    with(File("out.class")) {
        writeBytes(bytes)
    }

    val execution = ExecutionClassLoader(extensionClassLoader, ClassLoader.getPlatformClassLoader()).apply {
        putClassFile(bytes, "main")
    }


    execution.execute("main", "main")


}