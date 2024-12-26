package com.language.compilation

import com.language.BooleanOp
import com.language.CompareOp
import com.language.MathOp
import com.language.TemplatedType
import com.language.codegen.asUnboxed
import com.language.codegen.getOrDefault
import com.language.codegen.getOrNull
import com.language.codegen.lazyMap
import com.language.compilation.Instruction.DynamicCall
import com.language.compilation.metadata.*
import com.language.compilation.modifiers.Modifier
import com.language.compilation.modifiers.Modifiers
import com.language.compilation.templatedType.matches
import com.language.compilation.variables.*
import com.language.eval.*
import com.language.lexer.MetaInfo
import com.language.lookup.IRLookup
import com.language.lookup.oxide.lazyTransform
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.objectweb.asm.Label
import kotlin.RuntimeException


data class MetaData(val info: MetaInfo, val sourceFile: SignatureString)

fun MetaInfo.join(sourceFile: SignatureString) = MetaData(this, sourceFile)

class TypeError(
    val info: MetaData,
    override val message: String,
    val trace: List<Pair<MetaData, List<Type>>>
) : RuntimeException(message)

data class CallVariant(val func: IRFunction, val args: List<Type>)

class BasicHistory(
    private val histSet: MutableSet<CallVariant>,
    private val substitutions: MutableMap<IRFunction, MutableMap<List<Type.Broad>, Type.Broad>>,
    private val recHistSet: MutableSet<Pair<IRFunction, List<Type.Broad>>>
) : History {
    constructor() : this(mutableSetOf(), mutableMapOf(), mutableSetOf())

    override fun appendCallStack(variant: CallVariant) {
        histSet.add(variant)
    }

    override fun isPresent(variant: CallVariant): Boolean {
        return histSet.contains(variant)
    }

    override fun isPresent(function: IRFunction, variant: List<Type.Broad>): Boolean {
        return (function to variant) in recHistSet
    }

    override fun isSubstituted(function: IRFunction, variant: List<Type.Broad>): Type.Broad? = substitutions[function]?.get(variant)

    override fun appendRecCallStack(function: IRFunction, variant: List<Type.Broad>) {
        recHistSet.add(function to variant.toList())
    }

    override fun substitute(function: IRFunction, variant: List<Type.Broad>, type: Type.Broad) {
        substitutions[function] = (substitutions[function] ?: mutableMapOf()).apply { this[variant.toList()] = type }
    }

    override fun split(): History = BasicHistory(histSet.toMutableSet(), substitutions.toMutableMap(), recHistSet.toMutableSet())

}

interface History {
    fun appendCallStack(variant: CallVariant)

    fun isPresent(variant: CallVariant): Boolean
    fun isSubstituted(function: IRFunction, variant: List<Type.Broad>): Type.Broad?

    fun appendRecCallStack(function: IRFunction, variant: List<Type.Broad>)
    fun isPresent(function: IRFunction, variant: List<Type.Broad>): Boolean

    fun substitute(function: IRFunction, variant: List<Type.Broad>, type: Type.Broad)
    fun split(): History
}

sealed class Instruction {

    abstract val info: MetaData

    open fun genericChangeRequest(variables: VariableManager, name: String, type: Type) {
        //gracefully ignore if not overwritten
    }

    abstract suspend fun inferTypes(
        variables: VariableManager,
        lookup: IRLookup,
        handle: MetaDataHandle,
        hist: History
    ): TypedInstruction

    abstract suspend fun inferUnknown(
        variables: TypeVariableManager,
        lookup: IRLookup,
        handle: MetaDataTypeHandle,
        hist: History
    ): Type.Broad

    data class DynamicCall(
        val parent: Instruction,
        val name: String,
        val args: List<Instruction>,
        override val info: MetaData
    ) : Instruction() {

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val parent = parent.inferTypes(variables, lookup, handle, hist)

            val result = inferCall(
                args,
                name,
                parent,
                lookup,
                variables,
                handle,
                handle.inheritedGenerics,
                genericTypeChange = { name, type ->
                    genericChangeRequest(variables, name, type)
                },
                callBuilder = { candidate, typedArgs ->
                    TypedInstruction.DynamicCall(
                        candidate,
                        name,
                        parent,
                        typedArgs,
                        null
                    )
                },
                hist
            )
            return result
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            val argTypes = args.map { it.inferUnknown(variables, lookup, handle, hist) }
            return when (val parent = parent.inferUnknown(variables, lookup, handle, hist)) {
                is Type.Broad.Unset -> parent
                is Type.Broad.Known -> lookup.lookUpCandidateUnknown(parent.type, name, argTypes, hist)
                is Type.Broad.UnknownUnionized -> Type.Broad.Unset //if the entire type is not known,
                // there is no point in finding methods
            }
        }

        override fun genericChangeRequest(variables: VariableManager, name: String, type: Type) {
            parent.genericChangeRequest(variables, name, type)
        }
    }

    data class Keep(
        val value: Instruction,
        val name: String,
        val ownerSig: SignatureString,
        override val info: MetaData
    ) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val valueIns = value.inferTypes(variables, lookup, handle, hist)
            when (valueIns.type) {
                Type.Nothing -> error("Cannot keep instance of nothing")
                else -> {}
            }
            handle.appendKeepBlock(name, valueIns.type)
            return TypedInstruction.Keep(valueIns, name, ownerSig)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            return value.inferUnknown(variables, lookup, handle, hist)
        }

    }

    data class ConstArray(
        val arrayType: ArrayType,
        val items: List<ConstructingArgument>,
        override val info: MetaData
    ) : Instruction() {

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return when (items.all { it is ConstructingArgument.Normal }) {
                true -> constArray(variables, lookup, handle, hist)
                false -> notConstArray(variables, lookup, handle, hist)
            }
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }

        private suspend fun constArray(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction.LoadConstArray {
            val typedItems =
                items.map { (it as ConstructingArgument.Normal).item.inferTypes(variables, lookup, handle, hist) }
            val itemType = typedItems.map { it.type }.reduceOrNull { acc, type -> acc.join(type) }
            val broadType = itemType
                ?.let { Type.Broad.Known(it) }
                ?: Type.Broad.Unset
            return TypedInstruction.LoadConstArray(typedItems, arrayType, broadType)
        }

        private suspend fun notConstArray(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction.LoadArray {
            //temp variable for index storage
            variables.getTempVar(Type.IntT).use { indexStorage ->
                //temp variable for the array during construction
                variables.getTempVar(Type.Array(Type.Broad.Unset)).use { arrayStorage ->
                    val typedItems = items.map { it.inferTypes(variables, lookup, handle, hist) }

                    //get the common type of all items
                    val itemType = typedItems.itemType(lookup)

                    when (arrayType) {
                        ArrayType.Int -> if (!itemType.getOrDefault(Type.IntT)
                                .isContainedOrEqualTo(Type.IntT)
                        ) error("Type Error Primitive Int array can only hold IntT")

                        ArrayType.Double -> if (!itemType.getOrDefault(Type.DoubleT)
                                .isContainedOrEqualTo(Type.DoubleT)
                        ) error("Type Error Primitive Int array can only hold DoubleT")

                        ArrayType.Bool -> if (!itemType.getOrDefault(Type.BoolUnknown)
                                .isContainedOrEqualTo(Type.BoolUnknown)
                        ) error("Type Error Primitive Int array can only hold BoolT")

                        ArrayType.Object -> {}
                    }
                    return TypedInstruction.LoadArray(typedItems, arrayType, itemType, indexStorage.id, arrayStorage.id)
                }
            }
        }
    }

    sealed interface ConstructingArgument {

        suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedConstructingArgument

        @JvmInline
        value class Collected(private val item: Instruction) : ConstructingArgument {
            override suspend fun inferTypes(
                variables: VariableManager,
                lookup: IRLookup,
                handle: MetaDataHandle,
                hist: History
            ): TypedConstructingArgument {
                val item = item.inferTypes(variables, lookup, handle, hist)
                if (!item.type.isCollectable(lookup)) {
                    error("Type Error: Cannot collect ${item.type}")
                }
                return TypedConstructingArgument.Collected(item)
            }
        }

        @JvmInline
        value class Iteration(private val loop: ForLoop) : ConstructingArgument {
            override suspend fun inferTypes(
                variables: VariableManager,
                lookup: IRLookup,
                handle: MetaDataHandle,
                hist: History
            ): TypedConstructingArgument {
                return TypedConstructingArgument.Iteration(
                    loop.inferTypes(
                        variables,
                        lookup,
                        handle,
                        hist
                    ) as TypedInstruction.ForLoop
                )
            }

        }

        @JvmInline
        value class Normal(val item: Instruction) : ConstructingArgument {
            override suspend fun inferTypes(
                variables: VariableManager,
                lookup: IRLookup,
                handle: MetaDataHandle,
                hist: History
            ): TypedConstructingArgument {
                return TypedConstructingArgument.Normal(item.inferTypes(variables, lookup, handle, hist))
            }
        }
    }

    data class ConstArrayList(val items: List<ConstructingArgument>, override val info: MetaData) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return when {
                items.isEmpty() -> TypedInstruction.LoadList(emptyList(), Type.Broad.Unset, null)
                items.any { it is ConstructingArgument.Iteration } -> variables.getTempVar(Type.Object)
                    .use { variable ->
                        val typedItems = items.map { it.inferTypes(variables, lookup, handle, hist) }
                        val itemType = typedItems.itemType(lookup)
                        TypedInstruction.LoadList(typedItems, itemType, variable.id)
                    }

                else -> {
                    val typedItems = items.map { it.inferTypes(variables, lookup, handle, hist) }
                    val itemType = typedItems.itemType(lookup)
                    TypedInstruction.LoadList(typedItems, itemType, null)
                }
            }
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }

    }

    data class LoadConstString(val value: String, override val info: MetaData) : Instruction() {

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return TypedInstruction.LoadConstString(value)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            return Type.Broad.Known(Type.String)
        }
    }

    data class LoadConstInt(val value: Int, override val info: MetaData) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return TypedInstruction.LoadConstInt(value)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            return Type.Broad.Known(Type.IntT)
        }
    }

    data class LoadConstDouble(val value: Double, override val info: MetaData) : Instruction() {

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return TypedInstruction.LoadConstDouble(value)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            return Type.Broad.Known(Type.DoubleT)
        }
    }

    data class LoadConstBool(val value: Boolean, override val info: MetaData) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return TypedInstruction.LoadConstBoolean(value)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            return Type.Broad.Known(if (value) Type.BoolTrue else Type.BoolFalse)
        }
    }

    data class If(
        val cond: Instruction,
        val body: Instruction,
        val elseBody: Instruction?,
        override val info: MetaData
    ) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction = coroutineScope {
            val cond = cond.inferTypes(variables, lookup, handle, hist)
            if (cond.type !is Type.BoolT) {
                error("Condition must be of type boolean but was ${cond.type}")
            }


            val bodyVars = variables.clone()
            val bodyFuture = async { body.inferTypes(bodyVars, lookup, handle, hist.split()) }
            val elseBodyVars = variables.clone()
            val elseBodyFuture = async { elseBody?.inferTypes(elseBodyVars, lookup, handle, hist.split()) }

            val body = bodyFuture.await()
            val elseBody = elseBodyFuture.await()

            val (bodyAdjust, elseBodyAdjust) = variables.merge(listOf(bodyVars, elseBodyVars))

            if (cond is TypedInstruction.Const) {
                if (cond.value() as Boolean) {
                    return@coroutineScope body
                } else {
                    return@coroutineScope elseBody ?: TypedInstruction.Noop(Type.Nothing)
                }
            }

            return@coroutineScope TypedInstruction.If(
                cond,
                body,
                elseBody,
                bodyAdjust,
                elseBodyAdjust
            )
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            val cond = cond.inferUnknown(variables, lookup, handle, hist)
            val bodyVars = variables.branch()
            val body = body.inferUnknown(bodyVars, lookup, handle, hist.split())
            val elseBodyVars = variables.branch()

            val elseBody =
                elseBody?.inferUnknown(elseBodyVars, lookup, handle, hist.split()) ?: Type.Broad.Known(Type.Nothing)

            variables.merge(listOf(bodyVars, elseBodyVars))

            if (cond is Type.Broad.Known) {
                if (cond.type !is Type.BoolT) error("Condition must be of type boolean")

                when (cond.type.boolValue) {
                    true -> return body
                    false -> return elseBody
                    else -> {}
                }
            }

            return body.join(elseBody)
        }
    }


    data class For(val forLoop: ForLoop, override val info: MetaData) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return forLoop.inferTypes(variables, lookup, handle, hist)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }
    }

    data class While(val cond: Instruction, val body: Instruction, override val info: MetaData) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val cond = cond.inferTypes(variables, lookup, handle, hist)
            if (cond.type !is Type.BoolT) {
                error("Condition must be of type boolean vzt us")
            }
            val isInfinite = cond.type == Type.BoolTrue
            val bodyScope = variables.clone()
            //we execute it twice since types may change between the first and second iterations
            //that means, it has to survive 2 iterations then it's safe
            body.inferTypes(bodyScope, lookup, handle, hist)
            val body = body.inferTypes(bodyScope, lookup, handle, hist)
            variables.merge(listOf(bodyScope))

            return TypedInstruction.While(cond, body, isInfinite)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            cond.inferUnknown(variables, lookup, handle, hist)
            body.inferUnknown(variables, lookup, handle, hist)
            body.inferUnknown(variables, lookup, handle, hist)
            return Type.Broad.Known(Type.Nothing)
        }
    }

    data class ConstructorCall(
        val className: SignatureString,
        val args: List<Instruction>,
        override val info: MetaData
    ) : Instruction() {

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val args = args.map { it.inferTypes(variables, lookup, handle, hist) }
            val candidate = lookup.lookUpConstructor(className, args.map { it.type })
            return TypedInstruction.ConstructorCall(
                className,
                args,
                candidate,
                candidate.oxideReturnType
            )
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            return lookup.lookUpConstructorUnknown(className, args.map { it.inferUnknown(variables, lookup, handle, hist) })
        }
    }

    data class InvokeLambda(val lambdaParent: Instruction, val args: List<Instruction>, override val info: MetaData) :
        Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val parent = lambdaParent.inferTypes(variables, lookup, handle, hist)
            val args = args.map { it.inferTypes(variables, lookup, handle, hist) }

            //when lambda inlined
            if (parent is TypedInstruction.Lambda && parent in handle.inlinableLambdas) {
                val scope = variables.clone()
                val loadInstructions = mutableListOf<TypedInstruction>()
                for ((value, typedArg) in parent.args.zip(this.args).zip(args)) {
                    val (name, arg) = value
                    when (arg) {
                        is LoadVar -> {
                            val provider = variables.variables[arg.name] ?: error("Underlying arg doesnt exist")
                            scope.putVar(name, provider) //use the same provider with 2 names
                        }

                        else -> loadInstructions.add(typedArg)
                    }
                }
                val typedBody = parent.body.inferTypes(scope, lookup, handle, hist)
                variables.merge(listOf(scope))

                return TypedInstruction.MultiInstructions(
                    loadInstructions + typedBody,
                    variables.toVarFrame()
                )
            }
            //when lambda invoked
            val candidate = when (val tp = parent.type) {
                is Type.Lambda -> {
                    lookup.lookupLambdaInvoke(tp.signature, args.map { it.type }, hist)
                }

                else -> error("Cannot invoke non lambda")
            }
            return TypedInstruction.DynamicCall(candidate, "invoke", parent, args, null)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }

    }

    data class ModuleCall(
        val moduleName: SignatureString,
        val name: String,
        val args: List<Instruction>,
        override val info: MetaData
    ) : Instruction() {

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val args = args.map { it.inferTypes(variables, lookup, handle, hist) }
            lookup.processInlining(variables, moduleName, name, args, this.args, handle.inheritedGenerics, hist)
                ?.let { return it }
            val candidate = lookup.lookUpCandidate(moduleName, name, args.map { it.type }, hist)
            return TypedInstruction.ModuleCall(
                candidate,
                name,
                moduleName,
                args
            )
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            val argTypes = args.map { it.inferUnknown(variables, lookup, handle, hist) }
            return lookup.lookUpCandidateUnknown(moduleName, name, argTypes, hist)
        }
    }

    data class Not(
        val ins: Instruction,
        override val info: MetaData
    ) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val typedIns = ins.inferTypes(variables, lookup, handle, hist)
            if (typedIns.type != Type.BoolUnknown) {
                error("")
            }
            if (typedIns is TypedInstruction.Const) {
                return evalLogicNot(typedIns)
            }
            return TypedInstruction.Not(typedIns)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            val first = ins.inferUnknown(variables, lookup, handle, hist)
            if (first is Type.Broad.Known) {
                first.type as Type.BoolT

                if (first.type.boolValue != null) {
                    return Type.Broad.Known(Type.BoolT(!first.type.boolValue))
                }
            }

            return Type.Broad.Known(Type.BoolUnknown)
        }

    }

    data class LogicalOperation(
        val first: Instruction,
        val second: Instruction,
        val op: BooleanOp,
        override val info: MetaData
    ) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val first = first.inferTypes(variables, lookup, handle, hist)
            if (first.type !is Type.BoolT) error("Expected $first to have type BoolT")

            val second = second.inferTypes(variables, lookup, handle, hist)
            if (second.type !is Type.BoolT) error("Expected $second to have type BoolT")

            if (first is TypedInstruction.Const && second is TypedInstruction.Const) {
                return evalBoolLogic(first, second, op)
            }

            if (first is TypedInstruction.Const) {
                return partialEvalBoolLogic(first, second, op)
            }

            if (second is TypedInstruction.Const) {
                return partialEvalBoolLogic(second, first, op)
            }

            return TypedInstruction.LogicOperation(first, second, op)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            val first = first.inferUnknown(variables, lookup, handle, hist)
            if (first is Type.Broad.Typeful && first.type !is Type.BoolT) error("Expected $first to have type BoolT")

            val second = second.inferUnknown(variables, lookup, handle, hist)
            if (second is Type.Broad.Typeful && second.type !is Type.BoolT) error("Expected $first to have type BoolT")

            return Type.Broad.Known(Type.BoolUnknown)
        }

    }

    data class StaticCall(
        val classModuleName: SignatureString,
        val name: String,
        val args: List<Instruction>,
        override val info: MetaData
    ) : Instruction() {

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val args = args.map { it.inferTypes(variables, lookup, handle, hist) }
            val candidate = lookup.lookUpCandidate(
                classModuleName,
                name,
                args.map { it.type },
                hist,
                handle.inheritedGenerics.lazyTransform { _, it -> Type.Broad.Known(it) })
            return TypedInstruction.StaticCall(
                candidate,
                name,
                classModuleName,
                args
            )
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }
    }

    data class Math(val op: MathOp, val first: Instruction, val second: Instruction, override val info: MetaData) :
        Instruction() {


        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val first = first.inferTypes(variables, lookup, handle, hist)
            val second = second.inferTypes(variables, lookup, handle, hist)

            if (first is TypedInstruction.Const && second is TypedInstruction.Const) {
                return evalMath(first, second, op)
            }

            val resultType = typeMath(op, first.type, second.type)

            return TypedInstruction.Math(
                op,
                first,
                second,
                resultType
            )
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            val first = first.inferUnknown(variables, lookup, handle, hist)
            val second = second.inferUnknown(variables, lookup, handle, hist)

            if (first !is Type.Broad.Known || second !is Type.Broad.Known) {
                return Type.Broad.Unset
            }

            val result = typeMath(op, first.type, second.type)

            return Type.Broad.Known(result)
        }
    }

    data class StoreVar(val name: String, val value: Instruction, override val info: MetaData) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val value = value.inferTypes(variables, lookup, handle, hist)

            if (name == "_") {
                //don't store to a physical variable. ignore the result of the expression
                if (value is TypedInstruction.Const) {
                    return TypedInstruction.Noop(Type.Nothing)
                }
                return TypedInstruction.Ignore(value)
            }

            return when (value) {
                is TypedInstruction.Const -> {
                    variables.putVar(name, SemiConstBinding(value))
                    TypedInstruction.Noop(Type.Nothing)
                }

                else -> variables.changeVar(name, value)
            }
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            variables.set(name, value.inferUnknown(variables, lookup, handle, hist))
            return Type.Broad.Known(Type.Nothing)
        }
    }

    data class LoadVar(val name: String, override val info: MetaData) : Instruction() {


        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return variables.loadVar(name)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            return variables.get(name)
        }

        override fun genericChangeRequest(variables: VariableManager, name: String, type: Type) {
            variables.genericChangeRequest(this.name, name, type)
        }
    }

    data class MultiInstructions(val instructions: List<Instruction>, override val info: MetaData) : Instruction() {


        override fun genericChangeRequest(variables: VariableManager, name: String, type: Type) {
            instructions.lastOrNull()?.genericChangeRequest(variables, name, type)
        }

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val typedInstructions = mutableListOf<TypedInstruction>()
            for (instruction in instructions) {
                val typedIns = instruction.inferTypes(variables, lookup, handle, hist)
                typedInstructions.add(typedIns)
                if (typedIns.type == Type.Never) break //everything after that is dead code
            }
            if (typedInstructions.size == 1) return typedInstructions.first()
            return TypedInstruction.MultiInstructions(typedInstructions, variables.toVarFrame())
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad = instructions.map { it.inferUnknown(variables, lookup, handle, hist) }.last()

    }

    data class DynamicPropertyAccess(val parent: Instruction, val name: String, override val info: MetaData) :
        Instruction() {

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val parent = parent.inferTypes(variables, lookup, handle, hist)
            val returnType = when (val parentType = parent.type) {
                is Type.Union -> {
                    parentType.entries.map { lookup.lookUpFieldType(it, name) }.reduce { acc, type -> acc.join(type) }
                }

                else -> lookup.lookUpFieldType(parentType, name)
            }
            return TypedInstruction.DynamicPropertyAccess(
                parent,
                name,
                type = returnType
            )
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }
    }

    data class Lambda(
        val argNames: List<String>,
        val body: Instruction,
        val capturedVariables: List<String>,
        val imports: Set<SignatureString>,
        override val info: MetaData
    ) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val captures = capturedVariables.associateWith { variables.getType(it) }
            val sig = handle.addLambda(argNames, captures, body, handle.inheritedGenerics, imports)
            val candidate = lookup.lookupLambdaInit(sig)

            return TypedInstruction.Lambda(
                captures.mapValues { variables.loadVar(it.key) },
                sig,
                candidate,
                body,
                argNames,
            )
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }

    }

    data class DynamicPropertyAssignment(
        val parent: Instruction,
        val name: String,
        val value: Instruction,
        override val info: MetaData
    ) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val parent = parent.inferTypes(variables, lookup, handle, hist)

            val fieldType = lookup.lookUpFieldType(parent.type, name)
            val value = value.inferTypes(variables, lookup, handle, hist)
            if (!value.type.isContainedOrEqualTo(fieldType))
                error("Invalid type ${value.type} cannot be assigned to field $name of type $fieldType")

            return TypedInstruction.DynamicPropertyAssignment(parent, name, value)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }

    }

    //The type is unchecked
    data class Noop(val type: Type, override val info: MetaData) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return TypedInstruction.Noop(type)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }
    }


    data class Match(
        val parent: Instruction,
        val patterns: List<Pair<IRPattern, Instruction>>,
        override val info: MetaData
    ) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction = coroutineScope {
            val parent = parent.inferTypes(variables, lookup, handle, hist)

            val (typedPatterns, id) = withCastStoreId(
                this@Match.parent,
                parent.type,
                variables
            ) { castStoreId: VariableProvider ->
                //iterate over every pattern
                patterns.mapIndexed { i, (pattern, body) ->

                    val ctx = PatternMatchingContextImpl(
                        types = listOf(castStoreId),
                        isLast = i == patterns.lastIndex,
                        castStoreId = castStoreId.physicalId!!
                    )
                    //check patterns
                    val typedPattern = pattern.inferTypes(ctx, lookup, variables, handle, hist)

                    //asynchronously infer types of body
                    val bodyScope = variables.clone()
                    async {
                        val parentName = if (this@Match.parent is LoadVar) this@Match.parent.name else null
                        compileBodyBranch(body, typedPattern, bodyScope, parentName, lookup, handle, hist)
                    }


                }.awaitAll()
            }
            val adjustments = variables.merge(typedPatterns.map { it.third })
            val compiledBranches = typedPatterns
                .zip(adjustments)
                .map { (triple, adjustment) ->
                    CompiledMatchBranch(triple.first, triple.second, adjustment, triple.third.toVarFrame())
                }
            val instance = TypedInstruction.Match(parent, compiledBranches, temporaryId = id)
            return@coroutineScope instance
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }

        private inline fun <T> withCastStoreId(
            parent: Instruction,
            parentType: Type,
            variables: VariableManager,
            closure: (VariableProvider) -> T
        ): Pair<T, Int> {
            if (parent is LoadVar) {
                val prov = variables.variables[parent.name]
                if (prov?.physicalId != null) {
                    return closure(prov) to prov.physicalId!!
                }
            }
            val tempVar = variables.getTempVar(parentType)
            return tempVar.use { closure(TempVarBinding(tempVar)) } to tempVar.id

        }

        private suspend fun compileBodyBranch(
            body: Instruction,
            typedPattern: TypedIRPattern,
            bodyScope: VariableManager,
            parentName: String?,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): Triple<TypedIRPattern, TypedInstruction, VariableManager> {
            if (typedPattern is TypedIRPattern.Destructuring) {
                //populate the scope with bindings
                if (parentName != null) {
                    bodyScope.change(parentName, typedPattern.type)
                }
                typedPattern.bindings.map { (name, tp) ->
                    bodyScope.putVar(
                        name,
                        FieldBinding(TypedInstruction.LoadVar(typedPattern.castStoreId, typedPattern.type), tp, name)
                    )
                }
            }

            val typedBody = body.inferTypes(bodyScope, lookup, handle, hist)

            return Triple(typedPattern, typedBody, bodyScope)
        }

        private fun filterPatterns(
            patterns: List<Pair<IRPattern, Instruction>>,
            type: Type
        ): List<Pair<IRPattern, Instruction>> {
            return patterns.filter {
                checkPatternForType(it.first, type)
            }
        }

        private fun IRPattern.isExhaustiveForType(type: Type) = when (this) {
            is IRPattern.Binding -> true
            is IRPattern.Condition -> false
            is IRPattern.Destructuring -> TODO() // type.isContainedOrEqualTo(this.type) && !hasCondition()
        }

        private fun IRPattern.hasCondition(): Boolean = when (this) {
            is IRPattern.Binding -> false
            is IRPattern.Condition -> true
            is IRPattern.Destructuring -> patterns.any { it.hasCondition() }
        }

        private fun checkPatternForType(pattern: IRPattern, type: Type): Boolean {
            return when (pattern) {
                is IRPattern.Binding -> true
                is IRPattern.Condition -> checkPatternForType(pattern.parent, type)
                //if they have any overlaping types it is true
                is IRPattern.Destructuring -> TODO()// pattern.type.intersectsWith(type)
            }
        }
    }

    data class Try(val instruction: Instruction, override val info: MetaData) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val typedIns = instruction.inferTypes(variables, lookup, handle, hist)
            return when (val tp = typedIns.type) {
                is Type.Union -> {
                    val errorVariants = tp.entries.filter { lookup.hasModifier(it, Modifier.Error) }
                    if (errorVariants.isEmpty()) {
                        //eliminate the need for any try logic if we dont have an error variant
                        return typedIns
                    }
                    errorVariants.forEach { handle.issueReturnTypeAppend(it) }
                    TypedInstruction.Try(typedIns, errorVariants.map { (it as Type.JvmType).signature })
                }

                else -> typedIns
            }
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }

    }

    data class Catch(
        val instruction: Instruction,
        val errorType: TemplatedType?,
        override val info: MetaData
    ) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val ins = instruction.inferTypes(variables, lookup, handle, hist)
            val errorType = with(lookup) { errorType?.populate(emptyMap()) }
                ?: Type.BasicJvmType(SignatureString("java::lang::Throwable"))
            val errorTypes = findErrorTypes(errorType)

            return TypedInstruction.Catch(ins, errorTypes, errorType)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }

        private fun findErrorTypes(tp: Type): Set<SignatureString> =
            mutableSetOf<SignatureString>().apply { findErrorTypes(tp, this) }

        private fun findErrorTypes(tp: Type, errorTypes: MutableSet<SignatureString>) {
            when (tp) {
                is Type.Union -> tp.entries.forEach { findErrorTypes(it, errorTypes) }
                is Type.JvmType -> errorTypes.add(tp.signature)
                else -> error("Invalid error type")
            }
        }
    }

    data class Return(val instruction: Instruction, override val info: MetaData) : Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val returnValue = instruction.inferTypes(variables, lookup, handle, hist)
            handle.issueReturnTypeAppend(returnValue.type)
            return TypedInstruction.Return(returnValue, handle.returnLabel)
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }

    }

    data class StaticPropertyAccess(val parentName: SignatureString, val name: String, override val info: MetaData) :
        Instruction() {
        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return TypedInstruction.StaticPropertyAccess(
                parentName,
                name,
                lookup.lookUpFieldType(parentName, name)
            )
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            return Type.Broad.Known(lookup.lookUpFieldType(parentName, name))
        }
    }

    data class Pop(override val info: MetaData) : Instruction() {


        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return TypedInstruction.Pop
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }
    }

    data class Null(override val info: MetaData) : Instruction() {

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            return TypedInstruction.Null
        }

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }
    }

    data class Comparing(
        val first: Instruction,
        val second: Instruction,
        val op: CompareOp,
        override val info: MetaData
    ) : Instruction() {

        override suspend fun inferTypes(
            variables: VariableManager,
            lookup: IRLookup,
            handle: MetaDataHandle,
            hist: History
        ): TypedInstruction {
            val first = first.inferTypes(variables, lookup, handle, hist)
            val second = second.inferTypes(variables, lookup, handle, hist)

            if (first is TypedInstruction.Const && second is TypedInstruction.Const) {
                return evalComparison(first, second, op)
            }

            when (op) {
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

        override suspend fun inferUnknown(
            variables: TypeVariableManager,
            lookup: IRLookup,
            handle: MetaDataTypeHandle,
            hist: History
        ): Type.Broad {
            TODO("Not yet implemented")
        }
    }

}

fun Type.simplifyUnbox(): Type = when (this) {
    is Type.Union -> simplifyUnbox()
    else -> this
}

fun Type.Union.simplifyUnbox(): Type = when {
    entries.size == 1 -> entries.first().let { if (it.isBoxedPrimitive()) it.asUnboxed() else it }
    else -> this
}

data class PatternMatchingContextImpl(
    val types: List<VariableProvider>,
    override val isLast: Boolean,
    override val castStoreId: Int
) : PatternMatchingContext {
    private var ptr = 0

    override fun nextBinding(): VariableProvider = types[ptr++]
}

fun TypedInstruction.isConst() = this is TypedInstruction.Const
fun TypedConstructingArgument.isConst() = instruction.isConst()

data class ForLoop(
    val parent: Instruction,
    val name: String,
    val indexName: String?,
    val body: Instruction.ConstructingArgument,
    val info: MetaData
) {
    suspend fun inferTypes(
        variables: VariableManager, lookup: IRLookup, handle: MetaDataHandle,
        hist: History
    ): TypedInstruction {
        val parent = parent.inferTypes(variables, lookup, handle, hist)
        return when {
            IteratorI.validate(lookup, parent.type, hist) -> {
                val hasNext = lookup.lookUpCandidate(parent.type, "hasNext", emptyList<Type>(), hist)
                val next = lookup.lookUpCandidate(parent.type, "next", emptyList<Type>(), hist)

                compileForLoopBody(variables, next, hasNext, parent, lookup, handle, hist)
            }

            IterableI.validate(lookup, parent.type, hist) -> {
                val iterCall =
                    DynamicCall(this.parent, "iterator", emptyList(), info).inferTypes(variables, lookup, handle, hist)

                val hasNext = lookup.lookUpCandidate(iterCall.type, "hasNext", emptyList<Type>(), hist)
                val next = lookup.lookUpCandidate(iterCall.type, "next", emptyList<Type>(), hist)

                compileForLoopBody(variables, next, hasNext, iterCall, lookup, handle, hist)
            }

            else -> error("Invalid type ${parent.type}; it is neither an iterable nor an iterator")
        }
    }

    private suspend fun compileForLoopBody(
        variables: VariableManager,
        nextCall: FunctionCandidate,
        hasNextCall: FunctionCandidate,
        parent: TypedInstruction,
        lookup: IRLookup,
        handle: MetaDataHandle,
        hist: History
    ): TypedInstruction.ForLoop {
        val bodyScope = variables.clone()
        val itemId = bodyScope.change(name, nextCall.oxideReturnType)
        val indexId = indexName?.let { bodyScope.change(indexName, Type.IntT) }

        val bodyScopePre = bodyScope.clone()
        //infer it twice for non consts to unfold
        body.inferTypes(bodyScope, lookup, handle, hist)
        val adjustments = bodyScopePre.loopMerge(bodyScope, variables)
        val body = body.inferTypes(bodyScopePre, lookup, handle, hist)
        val postLoopAdjustments = variables.merge(listOf(bodyScopePre))[0]
        return TypedInstruction.ForLoop(
            parent,
            itemId,
            indexId,
            hasNextCall,
            nextCall,
            body,
            adjustments,
            postLoopAdjustments,
            bodyScope.toVarFrame()
        )
    }


}

interface PatternMatchingContext {
    fun nextBinding(): VariableProvider
    val isLast: Boolean
    val castStoreId: Int
}

sealed interface IRPattern {

    suspend fun inferTypes(
        ctx: PatternMatchingContext,
        lookup: IRLookup,
        variables: VariableManager,
        handle: MetaDataHandle,
        hist: History
    ): TypedIRPattern

    data class Binding(val name: String) : IRPattern {
        override suspend fun inferTypes(
            ctx: PatternMatchingContext,
            lookup: IRLookup,
            variables: VariableManager,
            handle: MetaDataHandle,
            hist: History
        ): TypedIRPattern {
            val binding = ctx.nextBinding()
            if (name in handle.inheritedGenerics) {
                return TypedIRPattern.Destructuring(
                    handle.inheritedGenerics[name]!!.asBoxed(),
                    emptyList(),
                    variables.getExternal(binding),
                    ctx.isLast,
                    ctx.castStoreId
                )
            }
            if (name.getOrNull(0)?.isUpperCase() == true) {
                println("Warning binding to uppercase name `$name`")
            }
            variables.putVar(name, binding)
            return TypedIRPattern.Binding(name, variables.getType(name))
        }
    }

    data class Destructuring(val type: TemplatedType, val patterns: List<IRPattern>) : IRPattern {

        override suspend fun inferTypes(
            ctx: PatternMatchingContext,
            lookup: IRLookup,
            variables: VariableManager,
            handle: MetaDataHandle,
            hist: History
        ): TypedIRPattern {
            val type = with(lookup) {
                type.populate(handle.inheritedGenerics).asBoxed()
                //we box it because there cant be primitives anyway,
                // but we still want matching for int
                // to result in Integer and so on
            }


            val signature = (type.asBoxed() as Type.JvmType).signature
            val binding = ctx.nextBinding()

            val orderedFields = lookup
                .lookUpOrderedFields(signature)
                .map { (name, it) -> name to with(lookup) { it.populate(handle.inheritedGenerics) } }
            val fieldBindings =
                orderedFields.map { (name, type) -> FieldBinding(binding.get(variables.parent), type, name) }

            val context = PatternMatchingContextImpl(fieldBindings, false, binding.physicalId!!)

            return TypedIRPattern.Destructuring(
                type = type,
                patterns = patterns.map { it.inferTypes(context, lookup, variables, handle, hist) },
                isLast = ctx.isLast,
                castStoreId = binding.physicalId!!,
                loadItem = variables.getExternal(binding)
            )

        }
    }

    data class Condition(val parent: IRPattern, val condition: Instruction) : IRPattern {
        override suspend fun inferTypes(
            ctx: PatternMatchingContext,
            lookup: IRLookup,
            variables: VariableManager,
            handle: MetaDataHandle,
            hist: History
        ): TypedIRPattern {
            val parent = parent.inferTypes(ctx, lookup, variables, handle, hist)
            val condition = condition.inferTypes(variables, lookup, handle, hist)
            return TypedIRPattern.Condition(parent, condition)
        }
    }
}


inline fun Type.Broad.mapKnown(closure: (tp: Type) -> Type) = when (this) {
    is Type.Broad.Known -> Type.Broad.Known(closure(type))
    Type.Broad.Unset -> this
    is Type.Broad.UnknownUnionized -> Type.Broad.Known(closure(type))
}


fun Type.intersectsWith(other: Type): Boolean = when {
    this == other -> true
    this is Type.Union && other is Type.Union -> entries.any { other.entries.any { o -> it.intersectsWith(o) } }
    this is Type.Union -> entries.any { it.isContainedOrEqualTo(other) }
    other is Type.Union -> other.entries.any { it.isContainedOrEqualTo(this) }
    else -> false
}

fun Type.Broad.isContainedOrEqualTo(other: Type.Broad) = when {
    this == other -> true
    this is Type.Broad.Unset && other is Type.Broad.Known -> false
    other is Type.Broad.Unset && this is Type.Broad.Known -> false
    this is Type.Broad.Known && other is Type.Broad.Known -> type.isContainedOrEqualTo(other.type)
    else -> error("Unreachable")
}

fun Type.isContainedOrEqualTo(other: Type): Boolean = when {
    this == other -> true
    this is Type.BoolT && other is Type.BoolT -> true
    this is Type.JvmType &&
            other is Type.JvmType &&
            signature == other.signature &&
            genericTypes.keys == other.genericTypes.keys -> genericTypes.all { (name, type) ->
        type.isContainedOrEqualTo(
            other.genericTypes[name]!!
        )
    }

    this is Type.Union -> entries.all { it.isContainedOrEqualTo(other) }
    other is Type.Union -> other.entries.any { this.isContainedOrEqualTo(it) }
    else -> false
}

enum class ArrayType {
    Int,
    Double,
    Bool,
    Object
}


suspend fun Type.isCollectable(lookup: IRLookup): Boolean {
    return this is Type.JvmArray || lookup.typeHasInterface(this, SignatureString("java::util::Collection"))
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
        val BoolTrue = BoolT(true)
        val BoolFalse = BoolT(false)
        val BoolUnknown = BoolT(null)
    }

    sealed interface JvmType : Type {
        val signature: SignatureString
        val genericTypes: Map<String, Broad>
    }

    sealed interface Broad {
        data object Unset : Broad
        sealed interface Typeful : Broad {
            val type: Type
        }

        data class Known(override val type: Type) : Typeful
        data class UnknownUnionized(override val type: Type) : Typeful
    }


    data class BasicJvmType(
        override val signature: SignatureString,
        override val genericTypes: Map<String, Broad> = mapOf()
    ) : JvmType {
        override val size: Int = 1

        override fun toString(): String = signature.toString() + if (genericTypes.isNotEmpty()) {
            "<" + genericTypes.entries.joinToString(", ") { "${it.key}: ${it.value}" } + ">"
        } else ""
    }


    sealed interface JvmArray : Type {
        val itemType: Broad
    }

    data class Array(override val itemType: Broad) : JvmArray {
        override val size: Int = 1
    }

    data object IntArray : JvmArray {
        override val itemType: Broad = Broad.Known(IntT)
        override val size: Int = 1
    }

    data object DoubleArray : JvmArray {
        override val itemType: Broad = Broad.Known(DoubleT)
        override val size: Int = 1
    }

    data object BoolArray : JvmArray {
        override val itemType: Broad = Broad.Known(BoolUnknown)
        override val size: Int = 1
    }

    data class BoolT(val boolValue: Boolean?) : Type {
        override val size: Int
            get() = 1

        override fun equals(other: Any?): Boolean {
            return other is BoolT && if (boolValue != null) other.boolValue == boolValue else true
        }

        override fun hashCode(): Int {
            return boolValue?.hashCode() ?: 0
        }
    }

    data object IntT : Type {
        override val size: Int = 1

        override fun toString(): String = "i32"
    }

    data object DoubleT : Type {
        override val size: Int = 2

        override fun toString(): String = "f64"
    }

    data object Null : Type {
        override val size: Int = 1

        override fun toString(): String = "null"
    }

    data class Union(val entries: Set<Type>) : Type {
        override val size: Int = entries.maxOf { it.size }

        override fun toString(): String = entries.joinToString(" | ") { it.toString() }
    }

    data class Lambda(val signature: SignatureString) : Type {
        override val size: Int = 1
    }

    //this is not null but represents an instruction not producing a value on the stack whatsoever
    data object Nothing : Type {
        override val size: Int = 0
    }

    //Representing a value never being completed
    data object Never : Type {
        override val size: Int = 0
    }

}

fun Type.Broad.join(other: Type.Broad): Type.Broad = when {
    this is Type.Broad.Unset && other is Type.Broad.Unset -> this
    this is Type.Broad.Unset && other is Type.Broad.Typeful -> Type.Broad.UnknownUnionized(other.type)
    other is Type.Broad.Unset && this is Type.Broad.Typeful -> Type.Broad.UnknownUnionized(this.type)
    other is Type.Broad.Known && this is Type.Broad.Known -> Type.Broad.Known(this.type.join(other.type))
    other is Type.Broad.Typeful && this is Type.Broad.Typeful -> Type.Broad.UnknownUnionized(this.type.join(other.type))
    else -> error("Should not be reachable")
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
        other == Type.Never -> this
        this == Type.Never -> other
        this == Type.Nothing && other == Type.Nothing -> Type.Nothing
        other == Type.Nothing && this != Type.Nothing -> error("Nothing cannot be in a union")
        this is Type.JvmType && other is Type.JvmType && signature == other.signature -> {
            //iterate over all generics and join their values
            val allGenerics = genericTypes.keys + other.genericTypes.keys
            val joinedGenerics = allGenerics.associateWith { name ->
                val first = genericTypes[name]?.getOrNull()
                val second = other.genericTypes[name]?.getOrNull()

                when {
                    first == null && second != null -> second.asBroadType()
                    first != null && second == null -> first.asBroadType()
                    else -> {
                        val type = second?.let { first?.join(second) }
                        val broadType = type?.let { Type.Broad.Known(it) } ?: Type.Broad.Unset

                        broadType
                    }
                }
            }

            Type.BasicJvmType(signature, joinedGenerics)
        }

        this is Type.Union && other is Type.Union -> Type.Union((entries.toList() + other.entries.toList()).toSet())
        this is Type.Union -> Type.Union((entries.toList() + other).toSet())
        other is Type.Union -> Type.Union((other.entries.toList() + this).toSet())
        else -> Type.Union(setOf(this, other))
    }
    return when {
        result is Type.Union && result.entries.size == 1 -> result.entries.first()
        else -> result
    }
}

fun typeMath(op: MathOp, first: Type, second: Type): Type {
    when {
        first is Type.Union && second is Type.Union -> {
            first.flatMapEntries { firstItem ->
                second.mapEntries { secondItem ->
                    typeMath(
                        op,
                        firstItem,
                        secondItem
                    )
                }
            }
        }

        first is Type.Union -> {
            first.mapEntries { item -> typeMath(op, item, second) }
        }

        second is Type.Union -> {
            second.mapEntries { item -> typeMath(op, first, item) }
        }
    }
    //now we don't need to worry about unions anymore they cant get past the when-statement above
    return when (op) {
        MathOp.Add -> {
            when {
                first == Type.String -> Type.String
                first == Type.IntT && second == Type.DoubleT -> Type.DoubleT
                first == Type.DoubleT && second == Type.IntT -> Type.DoubleT
                first == Type.IntT && second == Type.IntT -> Type.IntT
                first == Type.Int && second == Type.Double -> Type.Double
                first == Type.Double && second == Type.Int -> Type.Double
                first.isInt() && second.isInt() -> Type.IntT
                first == Type.Null -> error("Cannot do null + sth")
                else -> error("Cannot perform operation $first + $second")
            }
        }

        else -> {
            if (op == MathOp.Div && first == Type.IntT && second == Type.IntT) {
                return Type.DoubleT
            }
            when {
                first == Type.IntT && second == Type.DoubleT -> Type.DoubleT
                first == Type.DoubleT && second == Type.IntT -> Type.DoubleT
                first == Type.IntT && second == Type.IntT -> Type.IntT
                first == Type.Int && second == Type.Double -> Type.Double
                first == Type.Double && second == Type.Int -> Type.Double
                first == Type.Int && second == Type.Int -> Type.IntT
                first.isInt() && second.isInt() -> Type.IntT
                else -> error("Cannot perform operation $first $second")
            }
        }
    }
}

fun Type.toActualJvmType() = when (this) {
    is Type.Union -> Type.Object
    else -> this
}

interface IRFunction {
    val args: List<Pair<String, TemplatedType?>>
    val returnType: TemplatedType?
    val generics: Map<String, GenericType>
    val body: Instruction
    val imports: Set<SignatureString>
    val keepBlocks: MutableMap<String, Type>
    val module: LambdaAppender
    val shouldInline: Boolean

    fun checkedVariants(): Map<List<Type>, Pair<TypedInstruction, FunctionMetaData>>

    suspend fun inferUnknown(argTypes: List<Type.Broad>, lookup: IRLookup, generics: Map<String, Type>, hist: History, check: Boolean = true): Type.Broad
    suspend fun inferTypes(argTypes: List<Type>, lookup: IRLookup, generics: Map<String, Type>, hist: History): Type
    suspend fun generateInlining(
        args: List<TypedInstruction>,
        uncompiledArgs: List<Instruction>,
        variables: VariableManager,
        instance: TypedInstruction?,
        lookup: IRLookup,
        generics: Map<String, Type>,
        hist: History
    ): TypedInstruction
}


data class BasicIRFunction(
    override val args: List<Pair<String, TemplatedType?>>,
    override val returnType: TemplatedType?,
    override val generics: Map<String, GenericType>,
    override val body: Instruction,
    override val imports: Set<SignatureString>,
    override val module: LambdaAppender,
    override val shouldInline: Boolean
) : IRFunction {
    private val mutex = Mutex()
    private val checkedVariants: MutableMap<List<Type>, Pair<TypedInstruction, FunctionMetaData>> = mutableMapOf()

    private val checkedUnknownVariants: MutableMap<List<Type.Broad>, Type.Broad> = mutableMapOf()

    override val keepBlocks: MutableMap<String, Type> = mutableMapOf()

    override fun checkedVariants(): Map<List<Type>, Pair<TypedInstruction, FunctionMetaData>> {
        return checkedVariants
    }

    //this algorithm purposely neglects annotated types simply because it is always only called in combination with the normal algorithm,
    //thus not performing redundant checks
    override suspend fun inferUnknown(argTypes: List<Type.Broad>, lookup: IRLookup, generics: Map<String, Type>, hist: History, check: Boolean): Type.Broad {
        mutex.withLock {
            if (argTypes in checkedUnknownVariants) {
                return checkedUnknownVariants[argTypes]!!
            }
        }

        //if it is already substituted by anything, we want to return the substituded call
        if (check)
        hist.isSubstituted(this, argTypes)?.let { return it }

        //this means we have reached a seperate recursion loop within our own recusion
        if (check && hist.isPresent(this, argTypes)) {
            return Type.Broad.Known(inferRecursive(argTypes, lookup, generics, hist))
        }

        hist.appendRecCallStack(this, argTypes)

        val vars = TypeVariableManagerImpl()
        args.zip(argTypes).forEach { (name, tp) -> vars.set(name.first, tp) }
        val metaHandle = MetaDataTypeHandleImpl(inheritedGenerics = generics)

        val result = body.inferUnknown(vars, lookup, metaHandle, hist)

        return result
    }

    private suspend fun inferRecursive(argTypes: List<Type.Broad>, lookup: IRLookup, generics: Map<String, Type>, hist: History): Type {
        hist.substitute(this, argTypes, Type.Broad.Unset)
        val result = inferUnknown(argTypes, lookup, generics, hist, false)
        
        //when the type is solely unknown, the function can only ever call itself to produce the return value in other words it has to be nothing
        if (result is Type.Broad.Unset) {
            return Type.Nothing
        }
        
        //this means the return value did not depend on the recursive calls so we already know it for sure
        if (result is Type.Broad.Known) {
            return result.type
        }
        
        var prev = (result as Type.Broad.UnknownUnionized).type
        while(true) {
            val res = inferUnknown(argTypes, lookup, generics, hist, false)
            res as Type.Broad.Typeful
            if (res.type == prev) {
                return prev
            }
            hist.substitute(this, argTypes, res)
            prev = res.type
        }
    }

    override suspend fun inferTypes(argTypes: List<Type>, lookup: IRLookup, generics: Map<String, Type>, hist: History): Type {
        //generics = inherited generics form impl block or other context
        //inferredGenerics = generics specifically from the function itself inferred based on arg and return types.
        //this.generics = prototyped generics with constraints
        mutex.withLock {
            if (argTypes in checkedVariants) {
                return checkedVariants[argTypes]!!.second.returnType
            }
        }

        val variant = CallVariant(this, argTypes)
        if (hist.isPresent(variant)) {
            val broadArgs = argTypes.lazyMap { Type.Broad.Known(it) }
            val type = inferRecursive(broadArgs, lookup, generics, hist)
            mutex.withLock {
                checkedVariants[argTypes] = TypedInstruction.Noop(type) to FunctionMetaData(type, -1)
            }

            return type
        }

        hist.appendCallStack(variant)

        if (argTypes.size != this.args.size) {
            error("Expected ${this.args.size} but got ${argTypes.size} arguments when calling $args (TypeChecking)")
        }
        val metaDataHandle = FunctionMetaDataHandle(generics, module, emptyList(), null)
        val inferredGenerics = mutableMapOf<String, Type>()

        val variables =
            VariableManagerImpl.fromVariables(argTypes
                .zip(this.args)
                .associate { (tp, name) ->
                    if (name.second?.matches(tp, inferredGenerics, this.generics, lookup) == false) error("Invalid type mismatch $tp : ${name.second}")
                    name.first to tp
                }
            )

        val result = body.inferTypes(variables, lookup.newModFrame(imports), metaDataHandle, hist)


        val returnType =
            if (metaDataHandle.hasReturnType()) result.type.join(metaDataHandle.returnType) else result.type
        metaDataHandle.issueReturnTypeAppend(returnType)

        if (this.returnType?.matches(returnType, inferredGenerics, this.generics, lookup) == false) error("Invalid type mismatch return type $returnType : ${this.returnType}")
        mutex.withLock {
            keepBlocks.putAll(metaDataHandle.keepBlocks)
            checkedVariants[argTypes] =
                (result) to metaDataHandle.apply { varCount = variables.parent.varCount() }.toMetaData()
        }
        return returnType
    }

    override suspend fun generateInlining(
        args: List<TypedInstruction>,
        uncompiledArgs: List<Instruction>,
        variables: VariableManager,
        instance: TypedInstruction?,
        lookup: IRLookup,
        generics: Map<String, Type>,
        hist: History
    ): TypedInstruction {

        if (args.size != this.args.size - if (instance == null) 0 else 1) {
            error("Function expected ${this.args.size} arguments but got ${args.size}")
        }

        val scope = variables.clone()
        val actualArgInstruction = mutableListOf<TypedInstruction>()
        if (instance != null) {
            if (instance is TypedInstruction.LoadVar) {
                scope.putVar("self", VariableBinding(instance.id))
            } else {
                actualArgInstruction += scope.changeVar("self", instance)
            }
        }

        val slicedArgs = if (instance == null) this.args else this.args.slice(1..<this.args.size)
        val inlinableLambdas = mutableListOf<TypedInstruction.Lambda>()

        val inferredGenerics = mutableMapOf<String, Type>()

        args.zip(uncompiledArgs).zip(slicedArgs).forEach { (it, name) ->
            if (name.second?.matches(it.first.type, inferredGenerics, this.generics, lookup) == false) error("Type error mismatch ${it.first.type} : ${name.second}")
            val (typed, untyped) = it
            if (untyped is Instruction.LoadVar) {
                scope.reference(newName = name.first, oldName = untyped.name)
                return@forEach
            }
            when (typed) {
                is TypedInstruction.LoadVar -> {
                    scope.putVar(name.first, VariableBinding(typed.id))
                }

                is TypedInstruction.Lambda -> {
                    scope.putVar(name.first, ConstBinding(typed))
                    inlinableLambdas.add(typed)
                }

                is TypedInstruction.Const -> scope.putVar(name.first, SemiConstBinding(typed))
                else -> {
                    actualArgInstruction += scope.changeVar(name.first, typed)
                }
            }
        }
        val bodyInstruction = inferTypesInPlace(scope, lookup, generics, inlinableLambdas, hist)

        if (this.returnType?.matches(bodyInstruction.type, inferredGenerics, this.generics, lookup) == false) error("Type mismatch return type ${bodyInstruction.type} : ${this.returnType}")

        variables.mapping().minVarCount(scope.mapping().varCount())

        return TypedInstruction.MultiInstructions(
            actualArgInstruction + listOf(bodyInstruction),
            variables.toVarFrame()
        )
    }


    //compiles the function body in place with the given manager of the outside scope.
    private suspend fun inferTypesInPlace(
        variables: VariableManager,
        lookup: IRLookup,
        generics: Map<String, Type>,
        inlinableLambdas: List<TypedInstruction.Lambda>,
        hist: History
    ): TypedInstruction {
        val end = Label()
        val metaDataHandle = FunctionMetaDataHandle(generics, module, inlinableLambdas, end)
        val typedBody = body.inferTypes(variables, lookup, metaDataHandle, hist)
        metaDataHandle.issueReturnTypeAppend(typedBody.type)
        return TypedInstruction.InlineBody(typedBody, end, metaDataHandle.returnType)
    }

}

fun Type.isNumType() = isInt() || isDouble()
fun Type.isInt() = this == Type.Int || this == Type.IntT
fun Type.isDouble() = this == Type.Double || this == Type.DoubleT
fun Type.isBoolean() = this == Type.Bool || this is Type.BoolT
fun Type.isBoxedPrimitive() = this == Type.Int || this == Type.Double || this == Type.Bool
fun Type.isUnboxedPrimitive() = this == Type.IntT || this is Type.BoolT || this == Type.DoubleT
fun Type.asBoxed(): Type = when (this) {
    Type.IntT -> Type.Int
    Type.DoubleT -> Type.Double
    is Type.BoolT -> Type.Bool
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
        fun fromJVMNotation(string: String) = SignatureString(string.replace("/", "::"))
    }

    val structName
        get() = value.split("::").last()
    val modName
        get() = SignatureString(value.removeSuffix("::$structName"))

    fun toDotNotation() = value.replace("::", ".")
    fun toJvmNotation() = value.replace("::", "/")
    val oxideNotation
        get() = value

    val members: List<String>
        get() = value.split("::")

    fun chopOfStart(): SignatureString? = runCatching {
        SignatureString(members.slice(1..<members.size).joinToString("::"))
    }.getOrNull()

    override fun toString(): String = value
    operator fun plus(other: String) = SignatureString("$value::$other")
    operator fun plus(other: SignatureString) = SignatureString("$value::${other.value}")
}

data class GenericType(val modifiers: Modifiers, val upperBounds: List<SignatureString>)

data class IRStruct(
    val fields: Map<String, TemplatedType>,
    val generics: Map<String, GenericType>,
    val modifiers: Modifiers
) {
    private val mutex = Mutex()

    var defaultVariant: Map<String, Type>? = null
        private set

    suspend fun setDefaultVariant(defaultVariant: Map<String, Type>) {
        mutex.withLock {
            this.defaultVariant = defaultVariant
        }
    }
}

data class IRImpl(
    val fullSignature: SignatureString,
    val methods: Map<String, IRFunction>,
    val associatedFunctions: Map<String, IRFunction>,
    val genericModifiers: Map<String, GenericType>
)

data class IRModule(
    val name: SignatureString,
    val functions: Map<String, IRFunction>,
    val structs: Map<String, IRStruct>,
    val implBlocks: Map<TemplatedType, Set<IRImpl>>,
) : LambdaAppender {
    val lambdas: MutableMap<SignatureString, LambdaContainer> = mutableMapOf()
    private val mutex = Mutex()

    override suspend fun addLambda(
        argNames: List<String>,
        captures: Map<String, Type>,
        body: Instruction,
        generics: Map<String, Type>,
        imports: Set<SignatureString>
    ): SignatureString {
        val sig = name + SignatureString(generateName())
        val container = LambdaContainer(sig, captures, body, argNames, imports, generics, this)
        mutex.withLock {
            lambdas[sig] = container
        }
        return sig
    }

    suspend fun getLambda(signature: SignatureString): LambdaContainer? = mutex.withLock { lambdas[signature] }
}

data class LambdaContainer(
    val signature: SignatureString,
    val captures: Map<String, Type>,
    val closureBody: Instruction,
    val argNames: List<String>,
    val imports: Set<SignatureString>,
    val generics: Map<String, Type>,
    val module: LambdaAppender
) {
    private val mutex = Mutex()
    val checkedVariants: MutableMap<List<Type>, Pair<TypedInstruction, FunctionMetaData>> = mutableMapOf()

    suspend fun inferTypes(argTypes: List<Type>, lookup: IRLookup, hist: History): Type {
        if (argTypes in checkedVariants) {
            return checkedVariants[argTypes]!!.second.returnType
        }

        if (argTypes.size != this.argNames.size) {
            error("Expected ${this.argNames.size} but got ${argTypes.size} arguments when calling $argNames (TypeChecking)")
        }
        val metaDataHandle = FunctionMetaDataHandle(generics, module, emptyList(), null)
        val variables = VariableManagerImpl.fromVariables(
            argTypes.zip(argNames).associate { (tp, name) -> name to tp },
            reserveThis = true
        )
        captures.forEach { (name, type) ->
            variables.putVar(name, FieldBinding(TypedInstruction.LoadVar(0, Type.BasicJvmType(signature)), type, name))
        }
        val result = closureBody.inferTypes(variables, lookup.newModFrame(imports), metaDataHandle, hist)
        val requireImplicitNull = metaDataHandle.hasReturnType() && result.type == Type.Nothing

        val returnType = if (requireImplicitNull) Type.Null else result.type
        metaDataHandle.issueReturnTypeAppend(returnType)
        mutex.withLock {
            checkedVariants[argTypes] = (
                    if (requireImplicitNull)
                        TypedInstruction.MultiInstructions(
                            listOf(result, TypedInstruction.Null),
                            variables.toVarFrame()
                        )
                    else
                        result
                    ) to metaDataHandle.apply {
                varCount = variables.parent.varCount()
            }.toMetaData()
        }
        return returnType
    }
}

suspend inline fun inferCall(
    args: List<Instruction>,
    name: String,
    parent: TypedInstruction,
    lookup: IRLookup,
    variables: VariableManager,
    handle: MetaDataHandle,
    generics: Map<String, Type>,
    genericTypeChange: (name: String, type: Type) -> Unit,
    callBuilder: (candidate: FunctionCandidate, args: List<TypedInstruction>) -> TypedInstruction,
    hist: History
): TypedInstruction {
    val typedArgs = args.map { it.inferTypes(variables, lookup, handle, hist) }

    val changes = lookup.lookUpGenericTypes(parent.type, name, typedArgs.map { it.type })
    for ((changeName, tp) in changes) {
        genericTypeChange(changeName, tp)
    }

    return lookup.processInlining(variables, parent, name, typedArgs, args, generics, hist)
        ?.let { return it }
        ?: callBuilder(lookup.lookUpCandidate(parent.type, name, typedArgs.map { it.type }, hist), typedArgs)

}

fun Type?.asBroadType() = this?.let { Type.Broad.Known(it) } ?: Type.Broad.Unset

suspend fun List<TypedConstructingArgument>.itemType(lookup: IRLookup) = map {
    when (it) {
        is TypedConstructingArgument.Collected -> when (val tp = it.type) {
            is Type.Array -> (tp.itemType as? Type.Broad.Known)?.type
            is Type.BasicJvmType -> {
                if (lookup.typeHasInterface(tp, SignatureString("java::util::Collection"))) {
                    (tp.genericTypes["E"]!! as? Type.Broad.Known)?.type
                } else {
                    error("")
                }
            }

            else -> error("Invalid error type")
        }

        else -> it.type.asBoxed()
    }
}
    .reduceOrNull { acc, type -> type?.let { acc?.join(type) } ?: acc }
    .let { if (it == null) Type.Broad.Unset else Type.Broad.Known(it) }