package com.language.blackbox

import kotlin.test.Test

//Instead of compiling the entire std lib for the black box sample tests, this minimal testing lib is used.
val MinimalTestingLib = """
    use java::{lang::System, util::Scanner}
    inline func print(value) System.out.print(value)
    inline func println(value) System.out.println(value)
    
    use java::util::Random
    
    func randBool {
        random = keep { Random() }
        random.nextBoolean()
    }
    
    func panic(message) {
        print(message)
        while true {}
    }
    
    func read {
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
                Double.parseDouble(read())
            }
        """.trimIndent()

        runCode(code, input = "3.2").out("3\njava.lang.Integer\n3\njava.lang.Double\n").returnValue(3.2)
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
    fun testInferenceRecursiveFunctions() {
        //tests how ints are autmatically boxed to allow their boxed interface to be used
        val code = MinimalTestingLib + """
            func main {
                print(a(0, 2))
                a("Hello", 2)
            }
            
            func a(i, j) {
                if randBool() {
                     i
                } else {
                    a(i + 1, i + j) + j
                }
            }
        """.trimIndent()

        runCode(code).out { it.toIntOrNull() != null }
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
    fun testImplBlockAndInliningWithReturnKeyword() {
        val code = """
            use java::{util::List, lang::System}

            impl<T> List<T> {
                inline func first(self, closure) {
                    for item in self {
                        if closure(item) {
                            return item
                        }
                    }
                    null
                }
            }

            //some comment
            func main() { //some more comments
                result = list[1,2,3].first { |it| it * 2 == 4 }
                System.out.println(result)
            }

        """.trimIndent()
        runCode(code).out("2\n").returnValue(null)

    }


    @Test
    fun testOrDefaultApplicationOnNullableTypes() {
        val code = MinimalTestingLib+"""

            func main {
                result = if randBool() {
                    "Hello World"
                } else {
                    null
                }

                result = result.orDefault("Hello")
                print(result)
            }

            impl<T> T | null {
                func orDefault(self, value) {
                    match self {
                        null -> value
                        T -> self
                    }
                }
            }

        """.trimIndent()
        runCode(code).out("Hello", "Hello World").returnValue(null)
    }

    @Test
    fun testImplBlockGenericPassingForInlineFunctions() {
        val code = MinimalTestingLib+"""
            
            func main {
                result = if randBool() {
                    "Hello World"
                } else {
                    null
                }

                result = result.orDefault("Hello")
                print(result)
            }


            impl<T> T | null {
                inline func orDefault(self, value) {
                    match self {
                        null -> value
                        T -> self
                    }
                }
            }
        """.trimIndent()
        runCode(code).out("Hello", "Hello World").returnValue(null)
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

    @Test
    fun testGenericPassingOutOfFields() {
        val code = MinimalTestingLib+ """
           use java::util::List

           func main {
               instance = Foo(list[A()], list["Hello World"])

               for _ in instance.items {
                   print("A")
               }

               for item in instance.items2 {
                   print(item)
               }
           }

           struct A

           struct Foo {
               items List<A>,
               items2 List<str>
           }
        """.trimIndent()

        runCode(code).out("AHello World").returnValue(null)
    }

    @Test
    fun testPassingUnionOfStringAndNullIntoJavaFunctionAsString() {
        val code = MinimalTestingLib + """
            impl<T> T | null {
                inline func map(self, closure) {
                    match self {
                        null -> self
                        T -> closure(self)
                    }
                }
            }

            func main {
                result = example().map { |it| it.name }
                print(result)
            }
            struct A {
                name str
            }

            func example {
                if randBool() {
                    A("Hello")
                } else {
                    null
                }
            }

        """.trimIndent()
        runCode(code).out("Hello", "null").returnValue(null)

    }

    @Test
    fun testNeverTypesInMatchStatements()  {
        val code = """
        
        use java::{lang::System, util::{Scanner, Random}}
        inline func print(value) System.out.print(value)
        inline func println(value) System.out.println(value)
        
        
        func randBool {
            random = keep { Random() }
            random.nextBoolean()
        }
        
        func read {
            scanner = keep { Scanner(System.in) }
            scanner.nextLine()
        }
        
        
        impl<T> T | null {
            inline func unwrap(self, message) {
                match self {
                    null -> panic(message)
                    T -> self
                }
            }
        }
        
        func main {
            result = example().unwrap("expected value")
            print(result.toString())
        }
        
        struct A
        
        impl A {
            func toString(self) {
                "A"
            }
        }
        
        func panic(message) {
            println(message)
            while true {}
        }
        
        func magic(value) {
            if randBool() {
                value
            } else {
                value
            }
        }
        
        func example {
            if magic(true) {
                A()
            } else {
                null
            }
        }
        """.trimIndent()

        runCode(code).out("A").returnValue(null)
    }
}