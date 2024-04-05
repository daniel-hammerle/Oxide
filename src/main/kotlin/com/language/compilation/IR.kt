package com.language.compilation

import com.language.CompareOp
import com.language.MathOp
import com.language.Variable

interface VariableMapping {
    fun change(name: String, type: Type): Int
    fun getType(name: String): Type
    fun getId(name: String): Int
}

class VariableMappingImpl private constructor(
    private val variables: MutableMap<String, Type> = mutableMapOf(),
    private val variableIds: MutableMap<String, Int> = mutableMapOf()
) : VariableMapping {

    companion object {
        fun fromVariables(variables: Map<String, Type>) =  VariableMappingImpl().apply {
            variables.forEach { (name, type) -> insertVariable(name, type) }
        }
    }

    private val variableStack: MutableList<Boolean> = mutableListOf()

    override fun change(name: String, type: Type): Int {
        return when {
            //if it already existed and the new type has the same size, we can simply keep the variable slot
            variables[name]?.size == type.size -> {
                variables[name] = type
                variableIds[name]!!
            }
            //if it existed but now has a different size, we have to deallocate the old one and reallocate a new free space
            name in variables -> {
                val oldType = variables[name]!!
                val oldId = variableIds[name]!!
                //clear the old space
                for (i in oldId..<oldId+oldType.size -1) {
                    variableStack[i] = false
                }
                return insertVariable(name, type)
            }
            //if it didn't exist in the first place, we just allocate a variable
            else -> {
                insertVariable(name, type)
            }
        }

    }

    private fun insertVariable(name: String, type: Type): Int {
        variables[name] = type
        var index = -1

        for (i in 0..variableStack.lastIndex - type.size + 1) {
            for (j in 0..<type.size) {
                if (variableStack[i+j]) break
            }
            index = i
            break
        }

        when {
            index == -1 -> {
                variableStack.add(true)
                variableIds[name] = variableStack.lastIndex
                return variableStack.lastIndex
            }
            else -> {
                variableIds[name] = index
                return index
            }
        }
    }

    override fun getType(name: String): Type {
        return variables[name]!!
    }

    override fun getId(name: String): Int {
        return variableIds[name]!!
    }

    fun varCount(): Int = variableStack.size

}

sealed class Instruction {
    abstract fun type(variables: VariableMapping, lookup: IRModuleLookup): Type

    data class DynamicCall(
        val parent: Instruction,
        val name: String,
        val args: List<Instruction>,
    ) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            val parentType = parent.type(variables, lookup)
            val argTypes = args.map { it.type(variables, lookup) }

            //get the return type (or error out if we don't have the method with the respective args)
            val returnType = lookup.lookUpType(parentType, name, argTypes)
            return returnType
        }
    }

    data class LoadConstString(val value: String) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            return Type.String
        }
    }

    data class LoadConstInt(val value: Int) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            return Type.IntT
        }
    }
    data class LoadConstDouble(val value: Double) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            return Type.DoubleT
        }
    }
    data class LoadConstBool(val value: Boolean) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            return Type.BoolT
        }
    }
    data class If(val cond: Instruction, val body: Instruction, val elseBody: Instruction?) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            val condType = cond.type(variables, lookup)
            if (condType != Type.BoolT) {
                error("Condition must be of type boolean but was $condType")
            }
            val typeBody = body.type(variables, lookup)
            val typeElseBody = elseBody?.type(variables, lookup) ?: Type.Null
            return if (typeBody == typeElseBody) {
                typeBody
            } else {
                //when we have different types, we need to box it
                typeBody.asBoxed().join(typeElseBody.asBoxed())
            }
        }
    }

    data class While(val cond: Instruction, val body: Instruction) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            if (cond.type(variables, lookup) != Type.BoolT) {
                error("Condition must be of type boolean vzt us")
            }
            //while statements return null
            return Type.Nothing
        }
    }
    data class ConstructorCall(
        val className: SignatureString,
        val args: List<Instruction>,
    ) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            val argTypes = args.map { it.type(variables, lookup) }
            val returnType = lookup.lookUpConstructor(className, argTypes)
            return returnType
        }

    }

    data class ModuleCall(
        val moduleName: SignatureString,
        val name: String,
        val args: List<Instruction>,
    ) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            val argTypes = args.map { it.type(variables, lookup) }
            val returnType = lookup.lookUpType(moduleName, name, argTypes)
            return returnType
        }
    }
    data class StaticCall(
        val classModuleName: SignatureString,
        val name: String,
        val args: List<Instruction>,
    ) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            val argTypes = args.map { it.type(variables, lookup) }
            val returnType = lookup.lookUpType(classModuleName, name, argTypes)
            return returnType
        }
    }
    data class Math(val op: MathOp, val first: Instruction, val second: Instruction) : Instruction()  {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            val firstType = first.type(variables, lookup)
            val secondType = second.type(variables, lookup)
            return typeMath(op, firstType, secondType)
        }
    }
    data class StoreVar(val name: String, val value: Instruction) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            val valueType = value.type(variables, lookup)
            variables.change(name, valueType)
            return Type.Nothing
        }
    }
    data class LoadVar(val name: String) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            return variables.getType(name)
        }
    }
    data class MultiInstructions(val instructions: List<Instruction>) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            return instructions
                .map { it.type(variables, lookup) } //we need to execute every instruction to properly type check
                .lastOrNull() ?: Type.Null
        }
    }

    //its unknown since its dynamic
    data class DynamicPropertyAccess(val parent: Instruction, val name: String): Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            val parentType = parent.type(variables, lookup)
            return lookup.lookUpFieldType(parentType, name)
        }
    }
    data class StaticPropertyAccess(val parentName: SignatureString, val name: String): Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            return lookup.lookUpFieldType(parentName, name)
        }
    }

    data object Pop : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            return Type.Nothing
        }
    }
    data object Null : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            return Type.Null
        }
    }

    data class Comparing(
        val first: Instruction,
        val second: Instruction,
        val op: CompareOp
    ) : Instruction() {
        override fun type(variables: VariableMapping, lookup: IRModuleLookup): Type {
            val firstType = first.type(variables, lookup)
            val secondType = second.type(variables, lookup)

            return when(op) {
                //equal comparisons always work
                CompareOp.Eq, CompareOp.Neq -> Type.BoolT
                else -> when {
                    firstType.isNumType() && secondType.isNumType() -> Type.BoolT
                    else -> error("$firstType $op $secondType cannot be performed")
                }
            }
        }
    }
}

sealed interface Type {
    val size: Int

    companion object {
        val String = JvmType(SignatureString("java::lang::String"))
        val Int = JvmType(SignatureString("java::lang::Integer"))
        val Double = JvmType(SignatureString("java::lang::Double"))
        val Bool = JvmType(SignatureString("java::lang::Boolean"))
    }
    data class JvmType(val signature: SignatureString): Type {
        override val size: Int = 1
    }

    data object BoolT : Type {
        override val size: Int = 1
    }

    data object IntT : Type {
        override val size: Int = 1
    }
    data object DoubleT : Type {
        override val size: Int = 2
    }

    data object Null : Type {
        override val size: Int = 1
    }
    data class Union(val entries: Set<Type>) : Type {
        override val size: Int = entries.maxOf { it.size }
    }

    //this is not null but represents an instruction not producing a value on the stack whatsoever
    data object Nothing : Type {
        override val size: Int = 0
    }

}

fun Type.Union.mapEntries(closure: (item: Type) -> Type): Type.Union {
    return Type.Union(entries.map(closure).toSet())
}

fun Type.Union.flatMapEntries(closure: (item: Type) -> Type.Union): Type.Union {
    return Type.Union(entries.map(closure).flatMap { it.entries }.toSet())
}

fun Type.assertIsInstanceOf(other: Type) {
    when {
        this is Type.Union && other is Type.Union -> {
            this.entries.forEach { if (it !in other.entries) error("Type error $this is not instance of $other") }
        }
        other is Type.Union -> {
            if (other.entries.none { it == this }) {
                error("Type error $this is not instance of $other")
            }
        }
        //a union can never be an instance of a non-union
        this is Type.Union -> error("Type error $this is not instance of $other")
    }
}

fun Type.join(other: Type): Type {
    val result = when {
        this is Type.Union && other is Type.Union -> Type.Union((entries.toList() + other.entries.toList()).toSet())
        this is Type.Union -> Type.Union((entries.toList() + other).toSet())
        other is Type.Union -> Type.Union((other.entries.toList() + this).toSet())
        else -> Type.Union(setOf(this, other))
    }
    return when {
        result.entries.size == 1 -> result.entries.first()
        else -> result
    }
}

fun typeMath(op: MathOp, first: Type, second: Type): Type {
    when {
        first is Type.Union && second is Type.Union -> {
            first.flatMapEntries { firstItem -> second.mapEntries { secondItem -> typeMath(op, firstItem, secondItem) } }
        }
        first is Type.Union -> {
            first.mapEntries { item -> typeMath(op, item, second) }
        }
        second is Type.Union -> {
            second.mapEntries { item -> typeMath(op, first, item) }
        }
    }
    //now we don't need to worry about unions anymore they cant get past the when-statement above
    return when(op) {
        MathOp.Add -> {
            when{
                first == Type.String ->Type.String
                first == Type.IntT && second == Type.DoubleT -> Type.DoubleT
                first == Type.DoubleT && second == Type.IntT -> Type.DoubleT
                first == Type.IntT && second == Type.IntT -> Type.IntT
                first == Type.Int && second == Type.Double -> Type.Double
                first == Type.Double && second == Type.Int -> Type.Double
                first == Type.Int && second == Type.Int -> Type.Int
                first == Type.Null -> error("Cannot do null + sth")
                else -> error("Cannot perform operation $first + $second")
            }
        }
        else -> {
            if (op == MathOp.Div && first == Type.IntT && second == Type.IntT) {
                return Type.DoubleT
            }
            when{
                first == Type.IntT && second == Type.DoubleT -> Type.DoubleT
                first == Type.DoubleT && second == Type.IntT -> Type.DoubleT
                first == Type.IntT && second == Type.IntT -> Type.IntT
                first == Type.Int && second == Type.Double -> Type.Double
                first == Type.Double && second == Type.Int -> Type.Double
                first == Type.Int && second == Type.Int -> Type.Int
                else -> error("Cannot perform operation $first $second")
            }
        }
    }
}

data class IRFunction(val args: List<String>, val body: Instruction) {
    internal val checkedVariants: MutableMap<List<Type>, Type> = mutableMapOf()

    fun getVarCount(argTypes: List<Type>, lookup: IRModuleLookup): Int {
        if (argTypes.size != this.args.size) {
            error("Expected ${this.args.size} but got ${argTypes.size} arguments (TypeChecking)")
        }
        val variables = VariableMappingImpl.fromVariables(argTypes.zip(this.args).associate { (tp, name) -> name to tp })
        val result = body.type(variables, lookup)
        return variables.varCount()
    }

    fun type(argTypes: List<Type>, lookup: IRModuleLookup): Type {
        if (argTypes in checkedVariants) {
            return checkedVariants[argTypes]!!
        }

        if (argTypes.size != this.args.size) {
            error("Expected ${this.args.size} but got ${argTypes.size} arguments (TypeChecking)")
        }
        val variables = VariableMappingImpl.fromVariables(argTypes.zip(this.args).associate { (tp, name) -> name to tp })
        val result = body.type(variables, lookup)
        checkedVariants[argTypes] = result
        return result
    }
}

fun Type.isNumType() = isInt() || isDouble()
fun Type.isInt() = this == Type.Int || this == Type.IntT
fun Type.isDouble() = this == Type.Double || this == Type.DoubleT
fun Type.isBoolean() = this == Type.Bool || this == Type.BoolT
fun Type.isBoxed() = this == Type.Int || this == Type.Double
fun Type.isUnboxedPrimitive() = this == Type.IntT || this == Type.BoolT || this == Type.DoubleT
fun Type.asBoxed() = when(this) {
    Type.IntT -> Type.Int
    Type.DoubleT -> Type.Double
    Type.BoolT -> Type.Bool
    else -> this
}

@JvmInline
value class SignatureString(val value: String) {
    init {
        val pattern = """^[a-zA-Z0-9]+(::[a-zA-Z0-9]+)*$""".toRegex()
        if (!pattern.matches(value)) error("Invalid Signature string `$value`")
    }

    companion object {
        fun fromDotNotation(string: String) = SignatureString(string.replace(".", "::"))
    }

    val structName
        get() = value.split("::").last()
    val modName
        get() = SignatureString(value.removeSuffix("::$structName"))

    fun toDotNotation() = value.replace("::", ".")
    fun toJvmNotation() = value.replace("::", "/")
    val oxideNotation
        get() = value

    operator fun plus(other: String) = SignatureString("$value::$other")
    operator fun plus(other: SignatureString) = SignatureString("$value::${other.value}")
}

data class IRStruct(val fields: Map<String, Type>)

data class IRModule(val name: SignatureString, val functions: Map<String, IRFunction>, val structs: Map<String, IRStruct>)