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
#include <stdio.h>
#include <binaryen-c.h>
#include <jni.h>
#include <string>
#include <cstring>
#include <vector>

#define ERR_NOT_FOUND 1
#define ERR_TODO 2
#define ERR_TYPE_MISMATCH 3
#define ERR_UNREACHABLE 4
#define OK 0

#define COMMON_SIG "com.language.wasm.WasmInstruction$"

#define LOAD_I32 "LoadI32"
#define LOAD_F64 "LoadF64"
#define LOAD_BOOL "LoadBool"
#define LOAD_LOCAL "LoadLocal"
#define STORE_LOCAL "StoreLocal"
#define BLOCK "Block"
#define MATH "Math"
#define BREAK "Break"
#define IF "If"


static int GetSignature(JNIEnv *env, jobject object, jclass *outClass, std::string *outName)
{
    *outClass = env->GetObjectClass(object);
    if (outClass == nullptr)
    {
        return ERR_NOT_FOUND;
    }

    // Get java.lang.Class for the object
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == nullptr)
    {
        return ERR_NOT_FOUND;
    }

    // Get the getName() method from java.lang.Class
    jmethodID getNameMethod = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    if (getNameMethod == nullptr)
    {
        return ERR_NOT_FOUND;
    }

    jstring className = (jstring)env->CallObjectMethod(*outClass, getNameMethod);
    if (className == nullptr)
    {
        return ERR_NOT_FOUND;
    }

    const char *name = env->GetStringUTFChars(className, nullptr);
    *outName = std::string(name);

    env->ReleaseStringUTFChars(className, name);
    return OK;
}

#define TP_I32 0
#define TP_F64 1
#define TP_BOOL 2
#define TP_STR 3
#define TP_UNION 4
#define TP_PTR 5
#define TP_NOTHING 6
#define TP_NEVER 7
#define TP_I64 8
#define TP_F32 9

#define OP_ADD 0
#define OP_SUB 1
#define OP_MUL 2
#define OP_DIV 3
#define OP_MOD 4

static int ConvertTypeObjects(JNIEnv *env, jobject typeObject, BinaryenType *outType)
{
    jclass clazz = env->GetObjectClass(typeObject);

    // Get the ordinal of the enum
    jmethodID id = env->GetMethodID(clazz, "ordinal", "()I");
    if (id == nullptr)
    {
        return ERR_NOT_FOUND;
    }
    jint value = env->CallIntMethod(typeObject, id);

    // Switch to the binaryen types based on the ordinal:
    switch (value)
    {
    case TP_I32:
    case TP_BOOL:
    case TP_PTR:
    case TP_STR:
        *outType = BinaryenTypeInt32();
        break;
    case TP_F64:
        *outType = BinaryenTypeFloat64();
        break;
    case TP_F32:
        *outType = BinaryenTypeFloat32();
        break;
    case TP_I64:
    case TP_UNION:
        *outType = BinaryenTypeInt64();
        break;
    case TP_NEVER:
        *outType = BinaryenTypeUnreachable();
    default:
        return ERR_TYPE_MISMATCH;
    }

    return OK;
}

static int TranslateInstruction(JNIEnv *env, jobject wasmInstruction, BinaryenModuleRef module, BinaryenExpressionRef *outResult)
{

    printf("called ins");

    jclass clazz;
    std::string name;

    if (int err = GetSignature(env, wasmInstruction, &clazz, &name))
    {
        return err;
    }

    // Compare the common section of the signature ones
    for (int i = 0; i < strlen(COMMON_SIG); ++i)
    {
        if (name[i] != COMMON_SIG[i])
        {
            return ERR_TYPE_MISMATCH;
        }
    }

    // now we only care about the rest of the signature
    name = name.substr(strlen(COMMON_SIG));

    if (name == LOAD_I32)
    {
        // Retrieve the actual const value
        jmethodID id = env->GetMethodID(clazz, "getNumber", "()I");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }

        jint value = env->CallIntMethod(wasmInstruction, id);

        BinaryenLiteral literal = BinaryenLiteralInt32(value);

        *outResult = BinaryenConst(module, literal);
    }
    else if (name == LOAD_F64)
    {
        // Retrieve the actual const value
        jmethodID id = env->GetMethodID(clazz, "getNumber", "()D");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }

        jdouble value = env->CallDoubleMethod(wasmInstruction, id);

        BinaryenLiteral literal = BinaryenLiteralFloat64(value);

        *outResult = BinaryenConst(module, literal);
    }
    else if (name == LOAD_BOOL)
    {
        // Retrieve the actual const value
        jmethodID id = env->GetMethodID(clazz, "getBool", "()Z");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }

        jboolean value = env->CallBooleanMethod(wasmInstruction, id);

        BinaryenLiteral literal = BinaryenLiteralInt32(value);

        *outResult = BinaryenConst(module, literal);
    }
    else if (name == LOAD_LOCAL)
    {
        // Retrieve the actual const value
        jmethodID id = env->GetMethodID(clazz, "getLocalId", "()I");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jint index = env->CallIntMethod(wasmInstruction, id);

        id = env->GetMethodID(clazz, "getType", "()Lcom/language/wasm/WasmType;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobject typeObject = env->CallObjectMethod(wasmInstruction, id);

        BinaryenType tp;

        if (int err = ConvertTypeObjects(env, typeObject, &tp))
        {
            return err;
        }

        *outResult = BinaryenLocalGet(module, index, tp);
    }
    else if (name == BLOCK) 
    {
        jmethodID id = env->GetMethodID(clazz, "getInstructions", "()[Lcom/language/wasm/WasmInstruction;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobjectArray instructions = (jobjectArray) env->CallObjectMethod(wasmInstruction, id);

        jsize arrLength = env->GetArrayLength(instructions);
        
        std::vector<BinaryenExpressionRef> translatedInstructions(arrLength);
        for (int i = 0; i < arrLength; i++)
        {
            jobject instruction = env->GetObjectArrayElement(instructions, i);
            if (int err = TranslateInstruction(env, instruction, module, &translatedInstructions[i])) 
            {
                return err;
            }
        }

        id = env->GetMethodID(clazz, "getName", "()Ljava/lang/String;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jstring blockName = (jstring) env->CallObjectMethod(wasmInstruction, id);
        const char* name = env->GetStringUTFChars(blockName, nullptr);

        id = env->GetMethodID(clazz, "getType", "()Lcom/language/wasm/WasmType;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobject typeObj =  env->CallObjectMethod(wasmInstruction, id);

        BinaryenType type;
        if (int err = ConvertTypeObjects(env, typeObj, &type)) {
            return err;
        }

        *outResult = BinaryenBlock(module, name, translatedInstructions.data(), arrLength, type);
    }
    else if (name == MATH) 
    {
        jmethodID id = env->GetMethodID(clazz, "getFirst", "()Lcom/language/wasm/WasmInstruction;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobject firstObj =  env->CallObjectMethod(wasmInstruction, id);
        BinaryenExpressionRef first;

        if (int err = TranslateInstruction(env, firstObj, module, &first)) {
            return err;
        }

        id = env->GetMethodID(clazz, "getSecond", "()Lcom/language/wasm/WasmInstruction;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobject secondObj =  env->CallObjectMethod(wasmInstruction, id);
        BinaryenExpressionRef second;

        if (int err = TranslateInstruction(env, secondObj, module, &second)) {
            return err;
        }

        id = env->GetMethodID(clazz, "getType", "()Lcom/language/wasm/WasmType;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobject typeObj =  env->CallObjectMethod(wasmInstruction, id);

        id = env->GetMethodID(env->GetObjectClass(typeObj), "ordinal", "()I");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        int type =  env->CallIntMethod(typeObj, id);

        id = env->GetMethodID(clazz, "getOp", "()Lcom/language/MathOp;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobject opObj =  env->CallObjectMethod(wasmInstruction, id);

        id = env->GetMethodID(env->GetObjectClass(opObj), "ordinal", "()I");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        int nativeOp = env->CallIntMethod(typeObj, id);

        BinaryenOp op;

        switch(type) 
        {
        case TP_I32:
            switch(nativeOp)
            {
            case OP_ADD:
                op = BinaryenAddInt32();
                break;
            case OP_SUB:
                op = BinaryenSubInt32();
                break;
            case OP_MUL:
                op = BinaryenMulInt32();
                break;
            case OP_DIV:
                op = BinaryenDivSInt32();
                break;
            case OP_MOD:
                op = BinaryenRemSInt32();
                break;
            default:
                return ERR_UNREACHABLE;
            }
            break;
        case TP_I64:
            switch(nativeOp)
            {
            case OP_ADD:
                op = BinaryenAddInt64();
                break;
            case OP_SUB:
                op = BinaryenSubInt64();
                break;
            case OP_MUL:
                op = BinaryenMulInt64();
                break;
            case OP_DIV:
                op = BinaryenDivSInt64();
                break;
            case OP_MOD:
                op = BinaryenRemSInt64();
                break;
            default:
                return ERR_UNREACHABLE;
            }
            break;
        case TP_F32:
            switch(nativeOp)
            {
            case OP_ADD:
                op = BinaryenAddFloat32();
                break;
            case OP_SUB:
                op = BinaryenSubFloat32();
                break;
            case OP_MUL:
                op = BinaryenMulFloat32();
                break;
            case OP_DIV:
                op = BinaryenDivFloat32();
                break;
            default:
                return ERR_UNREACHABLE;
            }
            break;
        case TP_F64:
            switch(nativeOp)
            {
            case OP_ADD:
                op = BinaryenAddFloat64();
                break;
            case OP_SUB:
                op = BinaryenSubFloat64();
                break;
            case OP_MUL:
                op = BinaryenMulFloat64();
                break;
            case OP_DIV:
                op = BinaryenDivFloat64();
                break;
            default:
                return ERR_UNREACHABLE;
            }
            break;
        }
        

        *outResult = BinaryenBinary(module, op, first, second);
    }
    else if (name == BREAK) 
    {
        jmethodID id = env->GetMethodID(clazz, "getName", "()Ljava/lang/String;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jstring jname = (jstring) env->CallObjectMethod(wasmInstruction, id);
        const char* name = env->GetStringUTFChars(jname, nullptr);

        id = env->GetMethodID(clazz, "getValue", "()Lcom/language/wasm/WasmInstruction;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobject valueObj = (jobject) env->CallObjectMethod(wasmInstruction, id);
        
        BinaryenExpressionRef value = nullptr;
        if (valueObj != nullptr) 
        {
            if (int err = TranslateInstruction(env, valueObj, module, &value))
            {
                return err;
            }
        }
        *outResult = BinaryenBreak(module, name, nullptr, value);
    }
    else if (name == IF) 
    {
        printf("if reached\n");
        jmethodID id = env->GetMethodID(clazz, "getCondition", "()Lcom/language/wasm/WasmInstruction;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobject conditionObj = env->CallObjectMethod(wasmInstruction, id);

        BinaryenExpressionRef condition;
        if (int err = TranslateInstruction(env, conditionObj, module, &condition)) 
        {
            return err;
        }

        id = env->GetMethodID(clazz, "getBody", "()Lcom/language/wasm/WasmInstruction;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobject bodyObj = env->CallObjectMethod(wasmInstruction, id);

        BinaryenExpressionRef body;
        if (int err = TranslateInstruction(env, bodyObj, module, &body)) 
        {
            return err;
        }
        printf("body processed\n");


        id = env->GetMethodID(clazz, "getElseBody", "()Lcom/language/wasm/WasmInstruction;");
        if (id == nullptr)
        {
            return ERR_NOT_FOUND;
        }
        jobject elseBodyObj = env->CallObjectMethod(wasmInstruction, id);

        BinaryenExpressionRef elseBody = nullptr;
        if (elseBodyObj != nullptr) 
        {
            if (int err = TranslateInstruction(env, elseBodyObj, module, &elseBody)) 
            {
                return err;
            }
        }

        *outResult = BinaryenIf(module, condition, body, elseBody);
    }
    else
    {
        printf("%s is not yet implemented!", name);
        return ERR_TODO;
    }

    return OK;
}

static int TranslateFunction(JNIEnv *env, std::string moduleName, jobject functionObject, BinaryenModuleRef module)
{

    jclass clazz = env->GetObjectClass(functionObject);

    //Get the arguments
    jmethodID id = env->GetMethodID(clazz, "getArguments", "()[Lcom/language/wasm/WasmType;");
    if (id == nullptr)
    {
        return ERR_NOT_FOUND;
    }
    jobjectArray parameters = (jobjectArray)env->CallObjectMethod(functionObject, id);

    jsize arrayLength = env->GetArrayLength(parameters);

    //Transform the arguments into binaryen types.
    std::vector<BinaryenType> args(arrayLength);
    for (int i = 0; i < arrayLength; ++i)
    {
        jobject arg = env->GetObjectArrayElement(parameters, i);
        if (int err = ConvertTypeObjects(env, arg, &args[i]))
        {
            return err;
        }
    }

    printf("called func");


    //Combine them into a single binaryen type
    BinaryenType params = BinaryenTypeCreate(args.data(), args.size());

    //Get and transform retur type
    id = env->GetMethodID(clazz, "getReturnType", "()Lcom/language/wasm/WasmType;");
    if (id == nullptr)
    {
        return ERR_NOT_FOUND;
    }
    jobject returnTypeObject = env->CallObjectMethod(functionObject, id);

    BinaryenType returnType;
    if (int err = ConvertTypeObjects(env, returnTypeObject, &returnType))
    {
        return err;
    }

    //retrieve function body
    id = env->GetMethodID(clazz, "getBody", "()Lcom/language/wasm/WasmInstruction;");
    if (id == nullptr)
    {
        return ERR_NOT_FOUND;
    }
    jobject bodyObject = env->CallObjectMethod(functionObject, id);

    //Translate function body
    BinaryenExpressionRef body;
    if (int err = TranslateInstruction(env, bodyObject, module, &body))
    {
        return err;
    }

    //Get name and copy it into a string
    id = env->GetMethodID(clazz, "getName", "()Ljava/lang/String;");
    if (id == nullptr)
    {
        return ERR_NOT_FOUND;
    }
    jstring funcNameStr =(jstring) env->CallObjectMethod(functionObject, id);
    const char* rawName = env->GetStringUTFChars(funcNameStr, nullptr);
    std::string funcName = std::string(rawName);
    env->ReleaseStringUTFChars(funcNameStr, rawName);

    
    id = env->GetMethodID(clazz, "getLocals", "()[Lcom/language/wasm/WasmType;");
    if (id == nullptr)
    {
        return ERR_NOT_FOUND;
    }
    jobjectArray locals = (jobjectArray)env->CallObjectMethod(functionObject, id);

    arrayLength = env->GetArrayLength(locals);

    //Transform the arguments into binaryen types.
    std::vector<BinaryenType> localVars(arrayLength);
    for (int i = 0; i < arrayLength; ++i)
    {
        jobject arg = env->GetObjectArrayElement(locals, i);
        if (int err = ConvertTypeObjects(env, arg, &localVars[i]))
        {
            return err;
        }
    }

    //Register function
    BinaryenFunctionRef functionRef = BinaryenAddFunction(
        module, 
        (moduleName + funcName).c_str(), 
        params,
        returnType,
        localVars.data(),
        localVars.size(),
        body
    );


    return OK;
}

static int TranslateModule(JNIEnv *env, jobject moduleObject, BinaryenModuleRef module)
{

    jclass clazz = env->GetObjectClass(moduleObject);

    jmethodID id = env->GetMethodID(clazz, "getName", "()Ljava/lang/String;");
    if (id == nullptr) {
        return ERR_NOT_FOUND;
    }

    jstring jName = (jstring)env->CallObjectMethod(moduleObject, id);

    const char* nameRaw = env->GetStringUTFChars(jName, nullptr);

    std::string name = std::string(nameRaw);

    env->ReleaseStringUTFChars(jName, nameRaw);

    id = env->GetMethodID(clazz, "getFunctions", "()[Lcom/language/wasm/WasmFunction;");
    if (id == nullptr) {
        return ERR_NOT_FOUND;
    }

    jobjectArray functions = (jobjectArray)env->CallObjectMethod(moduleObject, id);

    jsize arrayLength = env->GetArrayLength(functions);

    for (int i = 0; i < arrayLength; i++) {
        jobject function = env->GetObjectArrayElement(functions, i);
        if (int err = TranslateFunction(env, name, function, module)) {
            return err;
        }
    }

    return OK;

}

static const char* ConvertErrorCodeToMessage(int code) {
    switch (code) {
    case ERR_NOT_FOUND:
        return "Expected function or field not found";
    case ERR_TODO:
        return "An operation is not implemented";
    case ERR_TYPE_MISMATCH:
        return "Type mismatch occured in instruction";
    case ERR_UNREACHABLE:
        return "This should not be reached";
    default:
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_language_wasm_WasmCodeGenBridge_generateWasm(JNIEnv *env, jobject /* this */, jobjectArray modules/*Array of modules*/)
{
    BinaryenModuleRef module = BinaryenModuleCreate();

    jsize arrayLength = env->GetArrayLength(modules);

    for (int i = 0; i < arrayLength; i++) {
        jobject moduleObject = env->GetObjectArrayElement(modules, i);


        if (int err = TranslateModule(env, moduleObject, module)) {
            const char* errorMessage = ConvertErrorCodeToMessage(err);
            jclass exceptionClass = env->FindClass("java/lang/RuntimeException");

            env->ThrowNew(exceptionClass, errorMessage);
            return nullptr;
        }
    }


        BinaryenModulePrint(module);


    BinaryenModuleAllocateAndWriteResult wasmResult = BinaryenModuleAllocateAndWrite(module, nullptr);

    jbyteArray result = env->NewByteArray(wasmResult.binaryBytes);
    env->SetByteArrayRegion(result, 0, wasmResult.binaryBytes, (jbyte *)wasmResult.binary);

    free(wasmResult.binary);

    BinaryenModuleDispose(module);

    return result;

}
