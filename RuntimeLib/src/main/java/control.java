import com.oxide.OxideInline;
import com.oxide.OxideType;
import com.oxide.TypeParameter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class control {
    private control() {}

    @Contract(value = "_ -> fail")
    public static void exit(int code) {
        System.exit(code);
    }

    public interface _Invokable {
        Object invoke_1();
    }


    public static Object OxideClosureUse(_Invokable closure) {
        return closure.invoke_1();
    }

    @OxideInline
    @Contract(pure = true)
    @TypeParameter(name = "T")
    public static @OxideType(signature = "T | java::lang::Throwable") Object _catch(@NotNull @OxideType(signature = "() -> T") _Invokable closure) {
        try {
            return OxideClosureUse(closure);
        } catch (Exception e) {
            return e;
        }
    }

}
