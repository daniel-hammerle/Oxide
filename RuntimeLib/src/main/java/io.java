import com.oxide.OxideInline;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;

public class io {
    @OxideInline
    public static void print(@Nullable Object any) {
        System.out.println(any);
    }

    @OxideInline
    public static void print(@Nullable Object... any) {
        System.out.println(Arrays.toString(any));
    }

    public static void print(int any) {
        System.out.println(any);
    }

    public static void print(boolean any) {
        System.out.println(any);
    }

}
