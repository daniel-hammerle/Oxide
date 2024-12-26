import std.random;

public class main {
    public static void main_1() {
        System.out.println("Hello World"); //std.io.println was inlined
        a_4325435346(0, 2);
        a_567477457("Hello", 2);
    }

    //variant with int was created
    public static int a_4325435346(int arg0, int arg1) {
        if (random.bool()) {
            return arg0;
        } else {
            return a_4325435346(arg0+1, arg0 + arg1) + arg1;
        }
    }

    //variant with int was created
    public static String a_567477457(String arg0, int arg1) {
        if (random.bool()) {
            return arg0;
        } else {
            return a_547346346(arg0+1, arg0 + arg1) + arg1;
        }
    }

    public static String a_547346346(String arg0, String arg1) {
        if (random.bool()) {
            return arg0;
        } else {
            return a_547346346(arg0+1, arg0 + arg1) + arg1;
        }
    }
}
