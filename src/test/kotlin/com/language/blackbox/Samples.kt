package com.language.blackbox

import kotlin.test.Test

val TestFunctions = """
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
        val code = TestFunctions + """
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
        val code = TestFunctions + """
            func main {
                x = if read() == "3" {
                    return
                } else {
                    23
                }
                print(x)
            }
        """.trimIndent()

        runCode(code, input = "3.2").out("23").returnValue(5.2)
    }

}