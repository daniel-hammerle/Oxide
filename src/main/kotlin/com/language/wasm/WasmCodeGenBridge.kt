package com.language.wasm

class WasmCodeGenBridge {
    init {
        System.load("/home/daniel/dev/DynamicJVMLangSpeedRun/build/libs/bindings.so")
    }

    external fun generateWasm(module: Array<WasmModule>): ByteArray

}