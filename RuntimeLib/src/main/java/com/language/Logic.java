package com.language;

import java.util.List;

public class Logic {
    public static boolean isTrue(Object any) {
        List.of(1, 2, 3);
        return switch (any) {
            case Boolean b -> b;
            default -> false;
        };
    }
}
