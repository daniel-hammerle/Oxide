import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class arrays {

    public static int len(Object[] array) {
        var path = Path.of("");
        return array.length;
    }

    @Contract(pure = true)
    public static @NotNull Object get(@NotNull Object[] array, int index) {
        return array[index];
    }

    @Contract(pure = true)
    public static int get(int @NotNull [] array, int index) {
        return array[index];
    }

    @Contract(pure = true)
    public static double get(double @NotNull [] array, int index) {
        return array[index];
    }

    public static boolean get(boolean @NotNull [] array, int index) {
        return array[index];
    }

    public static void set(Object[] array, int index, Object item) {
        array[index] = item;
    }

    public static void set(int[] array, int index, int item) {
        array[index] = item;
    }
    public static void set(double[] array, int index, double item) {
        array[index] = item;
    }
    public static void set(boolean[] array, int index, boolean item) {
        array[index] = item;
    }


}
