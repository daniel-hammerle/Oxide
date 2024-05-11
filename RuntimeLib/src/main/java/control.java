import org.jetbrains.annotations.Contract;


public class control {
    @Contract("_ -> fail")
    public static void exit(int code) {
        System.exit(code);
    }
}
