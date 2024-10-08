package com.language.blackbox

import kotlin.test.Test

val MinimalTestingLib = """
    use java::{lang::System, util::Scanner}
    inline func print(value) System.out.print(value)
    inline func println(value) System.out.println(value)
    
    func read() {
        scanner = keep { Scanner(System.in) }
        scanner.nextLine()
    }
""".trimIndent()

class Samples {
    @Test
    fun testHelloWorld() {
        val code = """
            use java::lang::System
            func main {
                System.out.println("Hello, World!")
            }
        """.trimIndent()

        runCode(code).out("Hello, World!\n").returnValue(null)
    }

    @Test
    fun testForLoops() {
        val code = """
            use java::lang::{System, Integer, Boolean}         
            
            impl null {
                func toString(self) {
                    "null"
                }
            }
            
            func main {
                items = list[1, 2, 3, "Hello World", false, null]
                for item in items {
                    System.out.print(item.toString() + ", ")
                }
                
                for item, index in items {
                    System.out.print(item.toString() + "," + index.toString() + " ")
                }
            }  
        """.trimIndent()

        runCode(code).out("1, 2, 3, Hello World, false, null, 1,0 2,1 3,2 Hello World,3 false,4 null,5 ").returnValue(null)
    }

    @Test
    fun testPrimitiveAutoBoxingForMethods() {
        //tests how ints are autmatically boxed to allow their boxed interface to be used
        val code = MinimalTestingLib + """
            use java::lang::Double
            func main {
                x = 3
                println(x.toString())
                println(x.getClass().getName())
                println(3.toString())
                println(3.2.getClass().getName())
                Double.parseDouble(read()) + 2
            }
        """.trimIndent()

        runCode(code, input = "3.2").out("3\njava.lang.Integer\n3\njava.lang.Double\n").returnValue(5.2)
    }

    @Test
    fun testBranchingAndEarlyReturn() {
        //tests how ints are autmatically boxed to allow their boxed interface to be used
        val code = MinimalTestingLib + """
            func main {
                foo(3)
                foo(-4)
            }
            
            func foo(y) {
                x = if y > 0 {
                    return
                } else {
                    y + 2
                }
                print(x)
            }
        """.trimIndent()

        runCode(code, input = "3.2").out("-2").returnValue(null)
    }

    @Test
    fun testReturn() {
        //tests how ints are autmatically boxed to allow their boxed interface to be used
        val code =  """
            func main {
                foo(-4)
                foo(3)
            }
            
            func foo(y) {
                x = if y > 0 {
                    return -37
                } else {
                    y + 2
                }
                return x * 2
            }
        """.trimIndent()

        runCode(code, input = "3.2").out("").returnValue(-37)
    }

    @Test
    fun testPatternMatching() {
        //tests how ints are autmatically boxed to allow their boxed interface to be used

        val code =  MinimalTestingLib+"""
            func main {
                instance = rand()
                
                match instance {
                    A(name, age >= 18) -> {
                        print(name)
                    }
                    B(name, _, _) -> {
                        print(name)
                    }
                }
            }
            
            struct A {
                name str,
                age i32
            }
            
            struct B {
                name str,
                email str,
                password str
            }
            
            use java::util::Random
            func rand {
                random = keep { Random() }
                if random.nextBoolean() {
                    A("Tim", 18)
                } else {
                    B("Tim", "tim@mail.co", "1234556")
                }
            }
            
        """.trimIndent()
        runCode(code).out("Tim").returnValue(null)
    }

    @Test
    fun testPatternMatchingInstanceCasting() {
        //tests how ints are autmatically boxed to allow their boxed interface to be used

        val code =  MinimalTestingLib+"""
            func main {
                instance = rand()
                match instance {
                    A(name, age >= 18) -> print(name)
                    B -> print(instance.name)
                }
            }
            
            struct A {
                name str,
                age i32
            }
            
            struct B {
                name str,
                email str,
                password str
            }
            
            use java::util::Random
            func rand {
                random = keep { Random() }
                if random.nextBoolean() {
                    A("Tim", 18)
                } else {
                    B("Tim", "tim@mail.co", "1234556")
                }
            }
            
        """.trimIndent()
        runCode(code).out("Tim").returnValue(null)
    }


}