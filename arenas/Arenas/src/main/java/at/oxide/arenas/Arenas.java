package at.oxide.arenas;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;
import org.openjdk.jol.vm.VirtualMachine;
import sun.misc.Unsafe;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;

public class Arenas {


    public static native Object allocateNative(String className);

    private static final VirtualMachine vm;

    static {
        try {
            System.load(new File("./arenas/native_arenas/target/release/libnative_arenas.so").getAbsolutePath());
        } catch (Throwable e) {
            e.printStackTrace();
        }
       vm = VM.current();

    }

    public static ClassRepr getRepr(String className) throws ClassNotFoundException {
        ClassRepr repr = ClassRepr.fromClassLayout(ClassLayout.parseClass(Class.forName(className)));
        System.out.println(repr);
        return repr;
    }


    public static long getFieldOffset(Field field) {
        return vm.fieldOffset(field);
    }


    public static String sanitizeClassName(String name) {
        return switch (name) {
            case "int" -> "I";
            case "double" -> "D";
            case "boolean" -> "Z";
            case "float" -> "F";
            case "char" -> "C";
            case "long" -> "J";
            case "short" -> "S";
            case "byte" -> "B";
            default -> name.startsWith("[") ? name : "L" + name.replace(".", "/") + ";";

        };
    }

    public static void main(String[] args)  {

        try {
            Object result = allocateNative("at.oxide.arenas.Foo");
            System.out.println(result );
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
