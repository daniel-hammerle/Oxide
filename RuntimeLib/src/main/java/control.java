import org.jetbrains.annotations.Contract;

import java.util.Arrays;


public class control<T> {
    private control() {}

    @Contract("_, !null -> fail")
    private<A> T doSth(int i, A value) {
        throw new AssertionError();
    }

    @Contract(value = "_ -> fail", pure = true)
    public static void exit(int code) {
        System.exit(code);
    }

}
