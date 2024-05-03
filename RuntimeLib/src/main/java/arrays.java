import java.util.Arrays;

public class arrays {

    public static int len(Object[] array) {
        return array.length;
    }

    public static Object get(Object[] array, int index) {
        return array[index];
    }

    public static int get(int[] array, int index) {
        return array[index];
    }

    public static double get(double[] array, int index) {
        return array[index];
    }

    public static boolean get(boolean[] array, int index) {
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
