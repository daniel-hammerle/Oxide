# Oxide 🧪

Welcome to **Oxide** – a cutting-edge programming language designed to be concise, flexible, and powerful. Oxide combines the ease and expressiveness of dynamically typed languages with the robustness and performance of statically typed languages.

## License

Oxide is licensed under the **Apache License 2.0**.  
See the [LICENSE](./LICENSE) file for details.


## Why Oxide? 🚀

### 🌟 **Dynamic Yet Type-Safe**
Oxide offers full type inference, eliminating the need for explicit type annotations while maintaining complete type safety. Write clean and readable code without sacrificing performance or safety.

### ⚡ **Efficient and Fast**
Functions and lambdas in Oxide are inlined, ensuring minimal overhead. Collection operations like `map`, `flatMap`, and `sumOf` are optimized for speed, outperforming Javas Stream Api.

### 🌐 **Public by Default**
Designed to reduce boilerplate, Oxide functions and variables are public by default. Focus on writing your logic, not on repetitive access modifiers.

### 🔒 **Controlled Unsafe Operations**
Oxide provides `std::unsafe` for byte casting primitives, enabling low-level operations when needed. This feature ensures memory safety while offering the flexibility required for specific algorithms.

### 🎨 **Concise and Readable**
Oxide's syntax is designed to be intuitive and expressive, reducing mental overhead and making your code easier to understand and maintain.

### 🧩 **Feature-Rich Standard Library**
Oxide comes with a robust standard library that supports a wide range of functionalities, from high-level collection operations to efficient low-level manipulations.

## Get Started 🌱

Oxide is currently in early alpha, and while it’s not yet production-ready, it already showcases many innovative features. Join us on this exciting journey as we continue to develop and refine Oxide.

Stay tuned for more updates, and feel free to explore the existing features and contribute your ideas!

---

Oxide – Redefining the boundaries of programming languages. 🔥

---

Happy coding with Oxide! 🧑‍💻

The goal of Oxide, to create an experience where developers feel like they are writing in a dynamically typed language like JavaScript, yet with the full benefits of type safety, is a compelling and ambitious vision. This design philosophy aims to combine the ease and flexibility of scripting languages with the robustness and reliability of statically typed languages. Here’s how Oxide achieves this:

### 1. **Implicit Type Inference**:
- **No Type Annotations**: In Oxide, you don’t need to explicitly declare types. The compiler infers types based on the context, making the code feel as dynamic as JavaScript.
- **Automatic Type Checking**: The compiler silently ensures that your code is type-safe, catching potential errors at compile-time without requiring explicit type annotations or extensive boilerplate.

### 2. **Seamless Interaction with Statically Typed Java Code**:
- **Deep Java Integration**: Oxide interacts seamlessly with Java libraries, parsing annotations like `@NotNull` and `@Nullable` to automatically enforce type constraints in a way that feels natural and unintrusive.
- **Implicitly Typed API**: You can call Java methods and interact with Java objects without worrying about types, yet Oxide ensures that all interactions are type-safe.

### 3. **Powerful Union Types**:
- **Flexible Functionality**: With Oxide’s union types, you can write functions and structures that behave polymorphically without needing interfaces or classes. The compiler ensures that operations on these unions are safe, even without explicit type declarations.
- **Duck Typing with Safety**: You can access methods or properties directly on union types as long as all members of the union support them, mimicking the flexibility of duck typing while maintaining type safety.

### 4. **Context-Aware Type Inference**:
- **Recursive Type Deduction**: Oxide’s compiler uses recursive type inference, leveraging the fact that all interactions eventually boil down to statically typed Java code. This allows for safe and accurate type inference without developer intervention.
- **Pattern Matching in Functions**: By allowing pattern matching directly in function definitions and supporting exhaustive `match` statements, Oxide makes conditional logic concise and expressive, yet safe.

### 5. **Error Handling as Values**:
- **Union-Based Error Handling**: Instead of using exceptions, Oxide handles errors through union types, which are automatically checked by the compiler. This allows for error handling that feels fluid and integrated into the language, much like in a dynamic language, but with the safety of static analysis.
- **Extension Functions**: With extension functions, you can add methods to any type, including handling errors, making the code more intuitive and reducing the need for verbose error handling logic.

### 6. **Optimized Performance**:
- **Inlining and Const Evaluation**: Oxide’s compiler is designed to optimize code aggressively. Functions are inlined, and constants are evaluated wherever possible, leading to efficient bytecode generation that competes with manually optimized Java code.
- **Dead Code Elimination**: The compiler detects and eliminates dead code, ensuring that the final output is lean and fast, even if the source code feels loose and dynamic.

### 7. **A Unified Experience**:
- **Fluid Syntax**: Oxide’s syntax is designed to be as unobtrusive as possible. Whether you’re returning a value, handling errors, or just writing straightforward logic, the syntax stays out of your way, making the experience feel smooth and natural.
- **Dynamic Feel, Static Guarantees**: The overall experience of writing in Oxide is meant to feel like you’re writing in a dynamically typed language. You can write code quickly and freely, yet you get the full benefits of compile-time type checking, optimizing the best of both worlds.

### Conclusion:
Oxide aims to make you feel like you’re writing in a dynamic language, giving you the freedom and flexibility to write code quickly and naturally. At the same time, the language’s design ensures that you benefit from the strong guarantees of a static type system, making your code safer, more reliable, and easier to maintain. This approach combines the ease of dynamic languages with the power and efficiency of static typing, making Oxide a unique and compelling choice for modern JVM development.