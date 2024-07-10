package com.language;


public class Logic {
    public static boolean isTrue(Object any) {
        return switch (any) {
            case Boolean b -> b;
            default -> false;
        };
    }
}
