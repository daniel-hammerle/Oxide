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
package com.language.lookup.jvm.parsing

import com.language.TemplatedType
import com.language.compilation.SignatureString
import com.language.compilation.Type
import com.language.compilation.modifiers.Modifier
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.modifiers.modifiers
import com.language.lookup.jvm.rep.defaultVariant
import org.objectweb.asm.*
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.util.LinkedList

typealias AsmType = org.objectweb.asm.Type


private fun AsmType.toOxideType(): Type {
    return when (this.sort) {
        AsmType.VOID -> Type.Nothing
        AsmType.BOOLEAN -> Type.BoolUnknown
        AsmType.CHAR -> error("Doesn't exist yet")
        AsmType.BYTE -> error("Doesn't exist yet")
        AsmType.SHORT -> error("Doesn't exist yet")
        AsmType.INT -> Type.IntT
        AsmType.FLOAT -> error("Doesn't exist yet")
        AsmType.LONG -> error("Doesn't exist yet")
        AsmType.DOUBLE -> Type.DoubleT
        AsmType.ARRAY -> Type.Array(elementType.toOxideType())
        AsmType.OBJECT -> Type.BasicJvmType(SignatureString.fromDotNotation(className), emptyMap())
        else -> throw IllegalArgumentException("Unsupported type: ${this.descriptor}")
    }
}

class ClassParser(
    private val reader: ClassReader,
    val signature: SignatureString
) {
    private val fields: MutableMap<String, TemplatedType> = mutableMapOf()
    private val staticFields: MutableMap<String, Type> = mutableMapOf()
    private val methods: MutableMap<String, MutableSet<FunctionInfo>> = mutableMapOf()
    private val associatedFunctions: MutableMap<String, MutableSet<FunctionInfo>> = mutableMapOf()
    private val constructors: MutableSet<FunctionInfo> = mutableSetOf()
    val generics = mutableListOf<GenericTypeParameter>()
    private val interfaces = mutableSetOf<SignatureString>()
    private var modifiers = Modifiers.Empty

    private fun appendMethod(name: String, info: FunctionInfo) {
        methods[name]?.add(info) ?: run {
            methods[name] = mutableSetOf(info)
        }
    }

    private fun appendAssociatedFunction(name: String, info: FunctionInfo) {
        associatedFunctions[name]?.add(info) ?: run {
            associatedFunctions[name] = mutableSetOf(info)
        }
    }

    private inner class InnerClassVisitor : ClassVisitor(Opcodes.ASM9) {

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<String>
        ) {
            val visitor = MethodSignatureVisitor(this@ClassParser.signature, name)
            if (signature != null)
                SignatureReader(signature).accept(visitor)
            modifiers = parseModifiersFormAsmAccessInt(access)
            this@ClassParser.interfaces.addAll(interfaces.map { SignatureString.fromDotNotation(it) })
            generics.addAll(visitor.newGenerics)
        }

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?
        ): FieldVisitor? {
            val visitor = FieldSignatureVisitor()
            val reader = SignatureReader(signature ?: descriptor)
            reader.accept(visitor)
            val fieldType = visitor.fieldType!!

            if (access and Opcodes.ACC_STATIC == Opcodes.ACC_STATIC) {
                //we can call default variant since static properties cannot be generic anyway
                staticFields[name] =fieldType.defaultVariant()
            } else {
                fields[name] = fieldType
            }

            return super.visitField(access, name, descriptor, signature, value)
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            val visitor = MethodSignatureVisitor(this@ClassParser.signature, name)
            val signatureReader = SignatureReader(signature ?: descriptor)
            signatureReader.accept(visitor)
            val info = visitor.toFunctionInfo()
            val kind = when {
                name == "<init>" -> MethodKind.Special
                access.hasBits(Opcodes.ACC_STATIC) -> MethodKind.Static
                else -> MethodKind.Virtual
            }
            val throwsExceptions = (exceptions ?: emptyArray()).map { SignatureString.fromJVMNotation(it) }.toMutableSet()
            return InnerMethodVisitor(kind, name, info, throwsExceptions)
        }
    }

    enum class MethodKind {
        Virtual,
        Static,
        Special
    }

    private inner class InnerMethodVisitor( val kind: MethodKind, val name: String, val info: FunctionInfo, val topLevelExceptions: MutableSet<SignatureString>) : MethodVisitor(Opcodes.ASM9) {
        val annotationVisitors = mutableSetOf<InnerAnnotationVisitor>()

        val exceptions = LinkedList<Pair<Label, MutableSet<SignatureString>>>()
        val mentions = mutableSetOf<FunctionMention>()

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
            val annotationType = AsmType.getType(descriptor).toOxideType()
            return InnerAnnotationVisitor(annotationType).also { annotationVisitors.add(it) }
        }

        override fun visitInsn(opcode: Int) {
            if (opcode != Opcodes.ATHROW) return
            println(opcode)
            //appendException(SignatureString.fromJVMNotation(type))
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean
        ) {
            val visitor = MethodSignatureVisitor(clazzName= SignatureString.fromJVMNotation(owner), funcName = name)
            SignatureReader(descriptor).accept(visitor)

            mentions.add(FunctionMention(
                SignatureString.fromJVMNotation(owner),
                name,
                visitor.toFunctionInfo().args
            ))
        }

        override fun visitLabel(label: Label) {
            exceptions.add(label to mutableSetOf())
        }

        override fun visitTryCatchBlock(start: Label, end: Label, handler: Label?, type: String) {
            val tp = SignatureString.fromJVMNotation(type)

            var i = exceptions.indexOfFirst { it.first == start }
            if (i < 0) {
                return
            }
            while(exceptions[i].first != end) {
                exceptions[i].second.remove(tp) //remove the type form any mentions within the try block
                i++
            }
        }

        private fun appendException(type: SignatureString) {
            val set = exceptions.lastOrNull()?.second ?: topLevelExceptions
            set.add(type)
        }

        override fun visitEnd() {
            val newAnnotations = annotationVisitors.map { it.toAnnotationInfo() }.toSet()

            val exceptions = topLevelExceptions + exceptions.flatMap { it.second } //collect all exceptions
            val exceptionInfo = ExceptionInfo(exceptions, mentions)

            val newInfo = info.copy(annotations = newAnnotations, exceptionInfo = exceptionInfo)
            when(kind) {
                MethodKind.Virtual -> appendMethod(name, newInfo)
                MethodKind.Static -> appendAssociatedFunction(name, newInfo)
                MethodKind.Special -> constructors.add(newInfo)
            }
            super.visitEnd()
        }
    }

    fun toClassInfo(): ClassInfo {
        reader.accept(
            InnerClassVisitor(),
            0
        )
        return ClassInfo(modifiers, signature, constructors, generics, fields, interfaces, staticFields, methods, associatedFunctions)
    }

    private inner class InnerAnnotationVisitor(val annotation: Type) : AnnotationVisitor(Opcodes.ASM9) {

        fun toAnnotationInfo(): AnnotationInfo {
            return AnnotationInfo((annotation as Type.JvmType).signature, items)
        }

        val items: MutableMap<String, Any> = mutableMapOf()
        override fun visit(name: String, value: Any) {
            items[name] = value
        }
    }
}

private class MethodSignatureVisitor(val clazzName: SignatureString, val funcName: String) : SignatureVisitor(Opcodes.ASM9) {
    val argumentTypes = mutableListOf<TemplatedType>()
    var returnType: TemplatedType? = null
    val newGenerics = mutableListOf<GenericTypeParameter>()
    private var currentGenericName: String? = null
    private val currentBounds = mutableListOf<String>()

    override fun visitFormalTypeParameter(name: String) {
        finishCurrentGeneric()
        currentGenericName = name
    }

    override fun visitClassBound(): SignatureVisitor {
        return this
    }

    override fun visitInterfaceBound(): SignatureVisitor {
        return this
    }

    override fun visitClassType(name: String) {
        currentBounds.add(name.replace('/', '.'))
    }

    override fun visitEnd() {
        finishCurrentGeneric()
    }

    private fun finishCurrentGeneric() {
        if (currentGenericName != null) {
            newGenerics.add(GenericTypeParameter(currentGenericName!!, currentBounds.map { SignatureString.fromDotNotation(it) }))
            currentBounds.clear()
            currentGenericName = null
        }
    }

    override fun visitParameterType(): SignatureVisitor {
        finishCurrentGeneric()
        return ArgumentTypeVisitor(argumentTypes)
    }

    override fun visitReturnType(): SignatureVisitor {
        finishCurrentGeneric()
        return ReturnTypeVisitor { returnType = it }
    }

    fun toFunctionInfo() = FunctionInfo(funcName, clazzName, newGenerics, argumentTypes, returnType!!, emptySet(), ExceptionInfo(emptySet(), emptySet()))

    private class ArgumentTypeVisitor(val argumentTypes: MutableList<TemplatedType>) : SignatureVisitor(Opcodes.ASM9) {
        override fun visitBaseType(descriptor: Char) {
            argumentTypes.add(baseType(descriptor))
        }

        override fun visitTypeVariable(name: String) {
            argumentTypes.add(TemplatedType.Generic(name))
        }

        override fun visitArrayType(): SignatureVisitor {
            return ArrayTypeVisitor { argumentTypes.add(TemplatedType.Array(it)) }
        }

        override fun visitClassType(name: String) {
            argumentTypes.add(TemplatedType.Complex(SignatureString(name.replace("/", "::")), emptyList()))
        }
    }

    private class ReturnTypeVisitor(val callback: (TemplatedType) -> Unit) : SignatureVisitor(Opcodes.ASM9) {
        override fun visitBaseType(descriptor: Char) {
            callback(baseType(descriptor))
        }

        override fun visitTypeVariable(name: String) {
            callback(TemplatedType.Generic(name))
        }

        override fun visitArrayType(): SignatureVisitor {
            return ArrayTypeVisitor(callback)
        }

        override fun visitClassType(name: String) {
            callback(TemplatedType.Complex(SignatureString.fromDotNotation(name.replace('/', '.')), emptyList()))
        }
    }


}

fun baseType(descriptor: Char): TemplatedType {
    return when (descriptor) {
        'I' -> TemplatedType.IntT
        'D' -> TemplatedType.DoubleT
        'Z' -> TemplatedType.BoolT
        'V' -> TemplatedType.Nothing
        else -> throw IllegalArgumentException("Unsupported base type: $descriptor")
    }
}

private class ArrayTypeVisitor(val callback: (TemplatedType) -> Unit) : SignatureVisitor(Opcodes.ASM9) {
    override fun visitBaseType(descriptor: Char) {
        callback(TemplatedType.Array(baseType(descriptor)))
    }

    override fun visitTypeVariable(name: String) {
        callback(TemplatedType.Array(TemplatedType.Generic(name)))
    }

    override fun visitArrayType(): SignatureVisitor {
        return ArrayTypeVisitor { callback(TemplatedType.Array(it)) }
    }

    override fun visitClassType(name: String) {
        callback(TemplatedType.Array(TemplatedType.Complex(SignatureString(name.replace("/", "::")), emptyList())))
    }

}

class FieldSignatureVisitor : SignatureVisitor(Opcodes.ASM9) {
    var fieldType: TemplatedType? = null

    override fun visitBaseType(descriptor: Char) {
        fieldType = baseType(descriptor)
    }

    override fun visitTypeVariable(name: String) {
        fieldType = TemplatedType.Generic(name)
    }

    override fun visitArrayType(): SignatureVisitor {
        return ArrayTypeVisitor { fieldType = TemplatedType.Array(it) }
    }

    override fun visitClassType(name: String) {
        fieldType = TemplatedType.Complex(SignatureString(name.replace('/', '.')), emptyList())
    }

}

fun parseModifiersFormAsmAccessInt(access: Int) = modifiers {
    //jvm stuff is always statically typed
    setModifier(Modifier.Typed)

    if (access.hasBits(Opcodes.ACC_STATIC)) setStatic()
    if (access.hasBits(Opcodes.ACC_PUBLIC)) setPublic()
}

fun Int.hasBits(bits: Int) = this and bits == bits