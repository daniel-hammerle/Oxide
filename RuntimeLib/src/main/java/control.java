import org.jetbrains.annotations.Contract;
import sun.misc.Unsafe;


public class control {
    private control() {}

    @Contract("_ -> fail")
    public static void exit(int code) {
        System.exit(code);
    }
}
