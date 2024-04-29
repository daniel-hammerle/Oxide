package com.language.compilation

import com.language.CompareOp
import com.language.MathOp
import com.language.codegen.VarFrame
import com.language.codegen.VarFrameImpl
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.UUID
import kotlin.math.max

sealed interface VariableMapping {
    fun change(name: String, type: Type): Int
    fun getType(name: String): Type
    fun getId(name: String): Int
    fun clone(): VariableMapping
    fun merge(branches: List<VariableMapping>): Pair<Map<String, Pair<Type, Type>>, Map<String, Pair<Type, Type>>>
    fun toVarFrame(): VarFrame
    fun deleteVar(name: String)
}


class TempVariable(private val variables: VariableMapping, val type: Type) : Closeable {
    private val name: String = "_${UUID.randomUUID()}"
    val id: Int = variables.change(name, type)

    override fun close() {
        variables.deleteVar(name)
    }

}

class VariableMappingImpl private constructor(
    private val variables: MutableMap<String, Type> = mutableMapOf(),
    private val variableIds: MutableMap<String, Int> = mutableMapOf(),
    private val variableStack: MutableList<Boolean>     = mutableListOf()
) : VariableMapping {

    companion object {
        fun fromVariables(variables: Map<String, Type>) =  VariableMappingImpl().apply {
            variables.forEach { (name, type) -> insertVariable(name, type) }
        }
    }

    override fun toVarFrame(): VarFrame {
        val variables: List<Type?> = variableStack.mapIndexed { index, b ->
            if (b)
                variables[variableIds.filter { it.value == index }.entries.first().key]
            else
                null
        }
        return VarFrameImpl(variables)
    }

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

    override fun merge(branches: List<VariableMapping>): Pair<Map<String, Pair<Type, Type>>, Map<String, Pair<Type, Type>>> {
        val changes = branches
            .filterIsInstance<VariableMappingImpl>()
            //find the variables that have changed types
            .map { branch ->
                branch.variables.filter { (varName, type) ->
                    varName in variables && variables[varName] != type
                }
            }
            //merge them into one and create unions when necessary
            .reduce { acc, map -> mergeMaps(acc, map) }
        //apply the changes to variables
        for ((name, type) in changes) {
            change(name, type)
        }

        return Pair(emptyMap(), emptyMap())
    }

    private fun mergeMaps(map1: Map<String, Type>, map2: Map<String, Type>): Map<String, Type> {
        val result = mutableMapOf<String, Type>()
        result.putAll(map1)
        for ((key, value) in map2) {
            result.merge(key, value) { existingValue, newValue ->
                existingValue.asBoxed().join(newValue.asBoxed())
            }
        }
        return result
    }


    override fun clone(): VariableMapping {
        return VariableMappingImpl(variables.toMutableMap(), variableIds.toMutableMap(), variableStack.toMutableList())
    }

    private fun insertVariable(name: String, type: Type): Int {
        variables[name] = type
        varMax = max(varMax, variables.size)
        var index = -1

        for (i in 0..variableStack.lastIndex - type.size + 1) {
            val isFree = (0..<type.size).all { !variableStack[i+it] }
            if (isFree) {
                index = i
                break
            }

        }

        when {
            index == -1 -> {
                variableStack.add(true)
                variableIds[name] = variableStack.lastIndex
                return variableStack.lastIndex
            }
            else -> {
                variableStack[index] = true
                variableIds[name] = index
                return index
            }
        }
    }

    override fun deleteVar(name: String) {
        val size = variables.remove(name)!!.size
        val id = variableIds.remove(name)!!
        for (offset in 0..<size) {
            variableStack[id+offset] = false
        }
    }

    override fun getType(name: String): Type {
        return variables[name]!!
    }

    override fun getId(name: String): Int {
        return variableIds[name]!!
    }

    private var varMax = 0
    fun varCount(): Int = varMax

}

sealed class Instruction {

    open fun genericChangeRequest(variables: VariableMapping, name: String, type: Type) {
        //ignore if not overwritten
    }

    abstract suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction

    data class DynamicCall(
        val parent: Instruction,
        val name: String,
        val args: List<Instruction>,
    ) : Instruction() {

        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction.DynamicCall {
            val parent = parent.inferTypes(variables, lookup)
            val args = args.map { it.inferTypes(variables, lookup) }

            //get the return type (or error out if we don't have the method with the respective args)
            val genericChanges = lookup.lookUpGenericTypes(parent.type, name, args.map { it.type })

            for ((name, index) in genericChanges) {
                val type = args[index].type
                //generics cant contain unboxed primitives
                genericChangeRequest(variables, name, type.asBoxed())
            }
            val newParent = this.parent.inferTypes(variables, lookup)
            val candidate = lookup.lookUpCandidate(newParent.type, name, args.map { it.type })

            return TypedInstruction.DynamicCall(
                candidate,
                name,
                newParent,
                args,
                null
            )
        }

        override fun genericChangeRequest(variables: VariableMapping, name: String, type: Type) {
            parent.genericChangeRequest(variables, name, type)
        }
    }

    data class ConstArray(val arrayType: ArrayType, val items: List<ConstructingArgument>) : Instruction() {

        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return when(items.all { it is ConstructingArgument.Normal }) {
                true -> constArray(variables, lookup)
                false -> notConstArray(variables, lookup)
            }
        }

        private suspend fun constArray(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction.LoadConstArray {
            val typedItems = items.map { (it as ConstructingArgument.Normal).item.inferTypes(variables, lookup) }
            val itemType = typedItems.map { it.type }.reduce { acc, type -> acc.join(type) }
            return TypedInstruction.LoadConstArray(typedItems, arrayType, itemType)
        }

        private suspend fun notConstArray(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction.LoadArray {
            TempVariable(variables, Type.IntT).use { indexStorage ->
                val typedItems = items.map { it.inferTypes(variables, lookup) }
                val itemType = typedItems.map { it.type }.reduce { acc, type -> acc.join(type) }
                TempVariable(variables, Type.Array(itemType)).use { arrayStorage ->
                    when(arrayType) {
                        ArrayType.Int -> if (!itemType.isContainedOrEqualTo(Type.IntT)) error("Type Error Primitive Int array can only hold IntT")
                        ArrayType.Double -> if (!itemType.isContainedOrEqualTo(Type.DoubleT)) error("Type Error Primitive Int array can only hold DoubleT")
                        ArrayType.Bool -> if (!itemType.isContainedOrEqualTo(Type.BoolT)) error("Type Error Primitive Int array can only hold BoolT")
                        ArrayType.Object -> {}
                    }
                    return TypedInstruction.LoadArray(typedItems, arrayType, itemType, indexStorage.id, arrayStorage.id)
                }
            }
        }
    }

    sealed interface ConstructingArgument {

        suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedConstructingArgument

        @JvmInline
        value class Collected(private val item: Instruction): ConstructingArgument {
            override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedConstructingArgument {
                val item = item.inferTypes(variables, lookup)
                if (!item.type.isCollectable(lookup)) {
                    error("Type Error: Cannot collect ${item.type}")
                }
                return TypedConstructingArgument.Collected(item)
            }
        }

        @JvmInline
        value class Normal(val item: Instruction) : ConstructingArgument {
            override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedConstructingArgument {
                return TypedConstructingArgument.Normal(item.inferTypes(variables, lookup))
            }
        }
    }

    data class ConstArrayList(val items: List<ConstructingArgument>) : Instruction() {
        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val typedItems = items.map { it.inferTypes(variables, lookup) }
            val itemType = typedItems.map { it.type }.reduce { acc, type -> acc.join(type) }
            return TypedInstruction.LoadList(typedItems, itemType)
        }

    }

    data class LoadConstString(val value: String) : Instruction() {

        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return TypedInstruction.LoadConstString(value)
        }
    }

    data class LoadConstInt(val value: Int) : Instruction() {
        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return TypedInstruction.LoadConstInt(value)
        }
    }
    data class LoadConstDouble(val value: Double) : Instruction() {

        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return TypedInstruction.LoadConstDouble(value)
        }
    }
    data class LoadConstBool(val value: Boolean) : Instruction() {
        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return TypedInstruction.LoadConstBoolean(value)
        }
    }
    data class If(val cond: Instruction, val body: Instruction, val elseBody: Instruction?) : Instruction() {
        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction = coroutineScope {
            val cond = cond.inferTypes(variables, lookup)
            if (cond.type != Type.BoolT) {
                error("Condition must be of type boolean but was ${cond.type}")
            }
            val bodyVars = variables.clone()
            val bodyFuture = async { body.inferTypes(bodyVars, lookup) }
            val elseBodyVars = variables.clone()
            val elseBodyFuture = async { elseBody?.inferTypes(elseBodyVars, lookup) }

            val body = bodyFuture.await()
            val elseBody = elseBodyFuture.await()
            val (changesBody, changesElseBody) = variables.merge(listOf(bodyVars, elseBodyVars))

            return@coroutineScope TypedInstruction.If(
                cond,
                body,
                elseBody,
                changesBody,
                changesElseBody
            )
        }
    }

    data class While(val cond: Instruction, val body: Instruction) : Instruction() {
        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val cond = cond.inferTypes(variables, lookup)
            if (cond.type != Type.BoolT) {
                error("Condition must be of type boolean vzt us")
            }
            val bodyScope = variables.clone()
            //we execute it twice since types may change between the first and second iterations
            //that means, it has to survive 2 iterations then it's safe
            body.inferTypes(bodyScope, lookup)
            val body = body.inferTypes(bodyScope, lookup)
            variables.merge(listOf(bodyScope))

            return TypedInstruction.While(cond, body)
        }
    }
    data class ConstructorCall(
        val className: SignatureString,
        val args: List<Instruction>,
    ) : Instruction() {

        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val args = args.map { it.inferTypes(variables, lookup) }
            val candidate = lookup.lookUpConstructor(className, args.map { it.type })
            return TypedInstruction.ConstructorCall(
                className,
                args,
                candidate,
               candidate.oxideReturnType
            )
        }
    }

    data class ModuleCall(
        val moduleName: SignatureString,
        val name: String,
        val args: List<Instruction>,
    ) : Instruction() {


        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val args= args.map { it.inferTypes(variables, lookup) }
            val candidate = lookup.lookUpCandidate(moduleName, name, args.map { it.type })
            return TypedInstruction.ModuleCall(
                candidate,
                name,
                moduleName,
                args
            )
        }
    }
    data class StaticCall(
        val classModuleName: SignatureString,
        val name: String,
        val args: List<Instruction>,
    ) : Instruction() {


        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val args= args.map { it.inferTypes(variables, lookup) }
            val candidate = lookup.lookUpCandidate(classModuleName, name, args.map { it.type })
            return TypedInstruction.StaticCall(
                candidate,
                name,
                classModuleName,
                args
            )
        }
    }
    data class Math(val op: MathOp, val first: Instruction, val second: Instruction) : Instruction()  {


        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val first = first.inferTypes(variables, lookup)
            val second = second.inferTypes(variables, lookup)
            val resultType = typeMath(op, first.type, second.type)

            return TypedInstruction.Math(
                op,
                first,
                second,
                resultType
            )
        }
    }
    data class StoreVar(val name: String, val value: Instruction) : Instruction() {
        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val value = value.inferTypes(variables, lookup)
            variables.change(name, value.type)
            return TypedInstruction.StoreVar(
                variables.getId(name),
                value
            )
        }
    }
    data class LoadVar(val name: String) : Instruction() {

        override fun genericChangeRequest(variables: VariableMapping, name: String, type: Type) {
            when(val varType = variables.getType(this.name)) {
                is Type.JvmType -> {
                    variables.change(this.name, varType.extendGeneric(name, type))
                }
                else -> {}
            }
        }

        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val type = variables.getType(name)
            return TypedInstruction.LoadVar(
                variables.getId(name),
                type
            )
        }
    }
    data class MultiInstructions(val instructions: List<Instruction>) : Instruction() {


        override fun genericChangeRequest(variables: VariableMapping, name: String, type: Type) {
            instructions.lastOrNull()?.genericChangeRequest(variables, name, type)
        }

        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return TypedInstruction.MultiInstructions(instructions.map { it.inferTypes(variables, lookup) }, variables.toVarFrame())
        }
    }

    //its unknown since its dynamic
    data class DynamicPropertyAccess(val parent: Instruction, val name: String): Instruction() {

        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val parent = parent.inferTypes(variables, lookup)

            val returnType = when(val parentType = parent.type) {
                is Type.Union -> {
                    parentType.entries.map { lookup.lookUpFieldType(it, name) }.reduce { acc, type -> acc.join(type) }
                }
                else ->lookup.lookUpFieldType(parentType, name)
            }
            return TypedInstruction.DynamicPropertyAccess(
                parent,
                name,
                type = returnType
            )
        }
    }

    //The type is unchecked
    data class Noop(val type: Type): Instruction() {
        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return TypedInstruction.Noop(type)
        }
    }


    data class Match(
        val parent: Instruction,
        val patterns: List<Pair<IRPattern, Instruction>>
    ) : Instruction() {
        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val parent = parent.inferTypes(variables, lookup)
            //val filteredPatterns = filterPatterns(patterns, parent.type)
            val typedPatterns = patterns.map { (pattern, body) ->
                val typedPattern = pattern.inferTypes(PatternMatchingContextImpl(listOf(Noop(parent.type))), lookup, variables)
                //populate the scope with bindings
                val bodyScope = variables.clone()
                typedPattern.bindings.map { bodyScope.change(it.first, it.second) }
                val typedBody = body.inferTypes(bodyScope, lookup)

                Triple(typedPattern, typedBody, bodyScope.toVarFrame())
            }
            //TODO check exhaustiveness
            return TypedInstruction.Match(parent, typedPatterns)
        }

        private fun filterPatterns(patterns: List<Pair<IRPattern, Instruction>>, type: Type): List<Pair<IRPattern, Instruction>> {
            return patterns.filter {
                checkPatternForType(it.first, type)
            }
        }


        private fun IRPattern.isExhaustiveForType(type: Type) = when(this) {
            is IRPattern.Binding -> true
            is IRPattern.Condition -> false
            is IRPattern.Destructuring -> type.isContainedOrEqualTo(this.type) && !hasCondition()
        }

        private fun IRPattern.hasCondition(): Boolean = when(this) {
            is IRPattern.Binding -> false
            is IRPattern.Condition -> true
            is IRPattern.Destructuring -> patterns.any { it.hasCondition() }
        }

        private fun checkPatternForType(pattern: IRPattern, type: Type): Boolean {
            return when(pattern) {
                is IRPattern.Binding -> true
                is IRPattern.Condition -> checkPatternForType(pattern.parent, type)
                //if they have any overlaping types it is true
                is IRPattern.Destructuring -> pattern.type.intersectsWith(type)
            }
        }
    }

    data class StaticPropertyAccess(val parentName: SignatureString, val name: String): Instruction() {
        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return TypedInstruction.StaticPropertyAccess(
                parentName,
                name,
                lookup.lookUpFieldType(parentName, name)
            )
        }
    }

    data object Pop : Instruction() {


        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return TypedInstruction.Pop
        }
    }
    data object Null : Instruction() {

        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return TypedInstruction.Null
        }
    }

    data class Comparing(
        val first: Instruction,
        val second: Instruction,
        val op: CompareOp
    ) : Instruction() {

        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            val first = first.inferTypes(variables, lookup)
            val second = second.inferTypes(variables, lookup)

            when(op) {
                CompareOp.Eq, CompareOp.Neq -> {}
                else -> when {
                    first.type.isNumType() && second.type.isNumType() -> {}
                    else -> error("${first.type} $op ${second.type} cannot be performed")
                }
            }

            return TypedInstruction.Comparing(
                first,
                second,
                op
            )
        }
    }

    data class Dup(val type: Type) : Instruction() {
        override suspend fun inferTypes(variables: VariableMapping, lookup: IRModuleLookup): TypedInstruction {
            return TypedInstruction.Dup(type)
        }
    }
}

data class PatternMatchingContextImpl(val types: List<Instruction>): PatternMatchingContext {
    private var ptr = 0

    override fun nextBinding(): Instruction = types[ptr++]
}

interface PatternMatchingContext {
    fun nextBinding(): Instruction
}

sealed interface IRPattern {

    suspend fun inferTypes(ctx: PatternMatchingContext, lookup: IRModuleLookup, variables: VariableMapping): TypedIRPattern

    data class Binding(val name: String): IRPattern {
        override suspend fun inferTypes(ctx: PatternMatchingContext, lookup: IRModuleLookup, variables: VariableMapping): TypedIRPattern.Binding {
            val binding = ctx.nextBinding()
            val ins = binding.inferTypes(variables, lookup)
            val varId = variables.change(name, ins.type)
            return TypedIRPattern.Binding(name, varId, ins)
        }
    }
    data class Destructuring(val type: Type, val patterns: List<IRPattern>) : IRPattern {

        init {
            when {
                type is Type.Union && patterns.isNotEmpty() -> error("When matching as a union accessing fields is not possible")
            }
        }

        override suspend fun inferTypes(ctx: PatternMatchingContext, lookup: IRModuleLookup, variables: VariableMapping): TypedIRPattern {
            val signature = (type.asBoxed() as Type.JvmType).signature
            val binding = ctx.nextBinding()
            val ins = binding.inferTypes(variables, lookup)
            if (ins.type.isContainedOrEqualTo(type)) {
                println("Useless branch (Impossible to reach $type with ${ins.type}")
            }
            val fields = lookup.lookUpOrderedFields(signature)

            val fieldInstructions = fields.map { (name, _) ->
                Instruction.DynamicPropertyAccess(Instruction.Noop(this.type), name)
            }
            val context = PatternMatchingContextImpl(fieldInstructions)
            return TypedIRPattern.Destructuring(type, patterns.map { it.inferTypes(context, lookup, variables) }, ins)
        }
    }
    data class Condition(val parent: IRPattern, val condition: Instruction) : IRPattern {
        override suspend fun inferTypes(ctx: PatternMatchingContext, lookup: IRModuleLookup, variables: VariableMapping): TypedIRPattern {
            val parent = parent.inferTypes(ctx, lookup, variables)
            val condition = condition.inferTypes(variables, lookup)
            return TypedIRPattern.Condition(parent, condition)
        }
    }
}



fun Type.intersectsWith(other: Type): Boolean = when {
    this == other -> true
    this is Type.Union && other is Type.Union -> entries.any { other.entries.any { o -> it.intersectsWith(o) } }
    this is Type.Union -> entries.any { it.isContainedOrEqualTo(other) }
    other is Type.Union -> other.entries.any { it.isContainedOrEqualTo(this) }
    else -> false
}

fun Type.BroadType.isContainedOrEqualTo(other: Type.BroadType) = when {
    this == other -> true
    this is Type.BroadType.Unknown && other is Type.BroadType.Known -> false
    other is Type.BroadType.Unknown && this is Type.BroadType.Known -> false
    this is Type.BroadType.Known && other is Type.BroadType.Known -> type.isContainedOrEqualTo(other.type)
    else -> error("Unreachable")
}

fun Type.isContainedOrEqualTo(other: Type): Boolean = when {
    this == other -> true
    this is Type.JvmType &&
            other is Type.JvmType &&
            signature == other.signature &&
            genericTypes.keys == other.genericTypes.keys -> genericTypes.all { (name, type) -> type.isContainedOrEqualTo(other.genericTypes[name]!!) }
    this is Type.Union -> entries.all { it.isContainedOrEqualTo(other) }
    other is Type.Union ->  other.entries.any { this.isContainedOrEqualTo(it) }
    else -> false
}

enum class ArrayType {
    Int,
    Double,
    Bool,
    Object
}


fun Type.isCollectable(lookup: IRModuleLookup): Boolean {
    return this is Type.Array || lookup.typeIsInterface(this, SignatureString("java::util::Collection"))
}

sealed interface Type {
    //size in the var stack
    //this is `1` for ints, booleans since 1 = 32bit
    //and its also `1` for complex types since they are pointers and ptrs in jvm are 32 bit
    //and its `2` for doubles or longs
    val size: Int

    companion object {
        val String = BasicJvmType(SignatureString("java::lang::String"))
        val Int = BasicJvmType(SignatureString("java::lang::Integer"))
        val Double = BasicJvmType(SignatureString("java::lang::Double"))
        val Bool = BasicJvmType(SignatureString("java::lang::Boolean"))
        val Object = BasicJvmType(SignatureString("java::lang::Object"))
    }
    sealed interface JvmType : Type {
        val signature: SignatureString
        val genericTypes: Map<String, BroadType>
    }

    sealed interface BroadType {
        data object Unknown : BroadType
        data class Known(val type: Type): BroadType
    }


    data class BasicJvmType(override val signature: SignatureString, override val genericTypes: Map<String, BroadType> = emptyMap()): JvmType {
        override val size: Int = 1
    }

    data class Array(val type: Type) : Type {
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

fun Type.JvmType.extendGeneric(name: String, type: Type): Type.JvmType {
    val generics = genericTypes.toMutableMap()
    when (val tp = generics[name]) {
        is Type.BroadType.Known -> generics[name] = Type.BroadType.Known(tp.type.join(type))
        Type.BroadType.Unknown -> generics[name] = Type.BroadType.Known(type)
        null -> error("No generic type $name")
    }

    return Type.BasicJvmType(signature, generics)
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

fun Type.toActualJvmType() = when(this) {
    is Type.Union -> Type.Object
    else -> this
}


data class IRFunction(val args: List<String>, val body: Instruction) {
    private val mutex = Mutex()
    private val checkedVariants: MutableMap<List<Type>, Pair<TypedInstruction, Int>> = mutableMapOf()

    fun checkedVariantsUniqueJvm(): Map<List<Type>, Pair<TypedInstruction, Int>> {
        return checkedVariants
    }

    suspend fun getVarCount(argTypes: List<Type>, lookup: IRModuleLookup): Int {
        if (argTypes.size != this.args.size) {
            error("Expected ${this.args.size} but got ${argTypes.size} arguments (TypeChecking)")
        }
        if (argTypes !in checkedVariants) {
            inferTypes(argTypes, lookup)
        }
        return checkedVariants[argTypes]!!.second
    }

    suspend fun inferTypes(argTypes: List<Type>, lookup: IRModuleLookup): TypedInstruction {
        if (argTypes in checkedVariants) {
            return checkedVariants[argTypes]!!.first
        }

        if (argTypes.size != this.args.size) {
            error("Expected ${this.args.size} but got ${argTypes.size} arguments when calling $args (TypeChecking)")
        }
        val variables = VariableMappingImpl.fromVariables(argTypes.zip(this.args).associate { (tp, name) -> name to tp })
        val result = body.inferTypes(variables, lookup)
        mutex.withLock {
            checkedVariants[argTypes] = result to variables.varCount()
        }
        return result
    }

}

fun Type.isNumType() = isInt() || isDouble()
fun Type.isInt() = this == Type.Int || this == Type.IntT
fun Type.isDouble() = this == Type.Double || this == Type.DoubleT
fun Type.isBoolean() = this == Type.Bool || this == Type.BoolT
fun Type.isBoxed() = this == Type.Int || this == Type.Double
fun Type.isUnboxedPrimitive() = this == Type.IntT || this == Type.BoolT || this == Type.DoubleT
fun Type.asBoxed(): Type = when(this) {
    Type.IntT -> Type.Int
    Type.DoubleT -> Type.Double
    Type.BoolT -> Type.Bool
    is Type.Union -> Type.Union(entries.map { it.asBoxed() }.toSet())
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