package com.language.compilation.variables

import UnsetListType
import com.language.compilation.ArrayType
import com.language.compilation.ScopeAdjustInstruction
import com.language.compilation.Type
import com.language.compilation.TypedInstruction
import listType
import union
import kotlin.test.Test
import kotlin.test.assertEquals


class VariableMappingTest {
    /**
     * Reflects the types of sourcecode like this
     * ```
     * x = list[]
     * if someCondition {
     *  x.add(1)
     * }
     * ```
     */
    @Test
    fun testGenericsMerging() {
        val manager = variables()
        manager.putVar("x", PhantomBinding(UnsetListType))
        assert(manager.hasVar("x"))
        assertEquals(UnsetListType, manager.getType("x"))
        val scope = manager.clone()
        scope.genericChangeRequest("x", "E", Type.Int)
        assertEquals(listType(Type.Int), scope.getType("x"))

        manager.merge(listOf(scope))
        assertEquals(listType(Type.Int), manager.getType("x"))
    }

    @Test
    fun testLoopMerging() {
        val vars = variables()
        vars.changeVar("items", TypedInstruction.LoadConstArray(listOf(TypedInstruction.LoadConstInt(234)), ArrayType.Object, Type.BroadType.Known(Type.Int)))
        val scope = vars.clone()
        scope.change("item", Type.Int)
        val scopePostFirst = scope.clone()
        val loopAdjustments = vars.loopMerge(scopePostFirst, vars)
        assertEquals(emptyList(), loopAdjustments.instructions)
        val adjust = vars.merge(listOf(scope))
        assertEquals(emptyList(), adjust[0].instructions)

    }

    @Test
    fun testGenericsMergingPhysical() {
        val manager = variables()
        manager.change("x", UnsetListType)
        assert(manager.hasVar("x"))
        assertEquals(UnsetListType, manager.getType("x"))
        val scope = manager.clone()
        scope.genericChangeRequest("x", "E", Type.Int)
        assertEquals(listType(Type.Int), scope.getType("x"))

        manager.merge(listOf(scope))
        assertEquals(listType(Type.Int), manager.getType("x"))
    }

    /**
     * Reflects the types of a sample like this
     * ```
     * x = list[]
     * if someCondition {
     *  x.add(1)
     * } else {
     *  x.add("Hello World")
     * }
     * ```
     */
    @Test
    fun testGenericsMultiMerging() {
        val manager = variables()
        //Put an unset list in the parent scope (list[])
        manager.putVar("x", PhantomBinding(UnsetListType))
        assert(manager.hasVar("x"))
        assertEquals(UnsetListType, manager.getType("x"))

        //Clone into 2 parallel scopes (for example, an if-else statement)

        //change type `E` in this branch to Int (for example by x.add(1))
        val scope = manager.clone()
        scope.genericChangeRequest("x", "E", Type.Int)
        assertEquals(listType(Type.Int), scope.getType("x"))

        //change the type `E` in this branch to String (for example, by x.add("Hello World")
        val scope2 = manager.clone()
        scope2.genericChangeRequest("x", "E", Type.String)
        assertEquals(listType(Type.String), scope2.getType("x"))

        //merge the 2 branches
        manager.merge(listOf(scope, scope2))

        //expect the correct outcome
        assertEquals(listType(union(Type.Int, Type.String)), manager.getType("x"))
    }


    @Test
    fun testGenericsMultiMergingPhysical() {
        val manager = variables()
        //Put an unset list in the parent scope (list[])
        manager.change("x", UnsetListType)
        assert(manager.hasVar("x"))
        assertEquals(UnsetListType, manager.getType("x"))

        //Clone into 2 parallel scopes (for example, an if-else statement)

        //change type `E` in this branch to Int (for example by x.add(1))
        val scope = manager.clone()
        scope.genericChangeRequest("x", "E", Type.Int)
        assertEquals(listType(Type.Int), scope.getType("x"))

        //change the type `E` in this branch to String (for example, by x.add("Hello World")
        val scope2 = manager.clone()
        scope2.genericChangeRequest("x", "E", Type.String)
        assertEquals(listType(Type.String), scope2.getType("x"))

        //merge the 2 branches
        manager.merge(listOf(scope, scope2))

        //expect the correct outcome
        assertEquals(listType(union(Type.Int, Type.String)), manager.getType("x"))
    }
}

fun variables() = VariableManagerImpl.fromVariables(mapOf())