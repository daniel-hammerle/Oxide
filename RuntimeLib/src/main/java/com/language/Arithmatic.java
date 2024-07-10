package com.language;

import org.jetbrains.annotations.Contract;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Arithmatic {
    @Contract("_, t -> fail")
    public static Object add(Object first, Object second) {
        return switch(first) {
            case String s -> s + second.toString();
            case Integer i -> switch (second) {
                case Integer j -> i + j;
                case Double j -> i + j;
                default -> throw new IllegalStateException("Cannot perform operation");
            };
            case Double i -> switch (second) {
                case Integer j -> i + j;
                case Double j -> i + j;
                default -> throw new IllegalStateException("Cannot perform operation");
            };
            default -> throw new IllegalStateException("Cannot perform operation");
        };
    }

    public static Object subtract(Object first, Object second) {
        return switch(first) {
            case Integer i -> switch (second) {
                case Integer j -> i - j;
                case Double j -> i - j;
                default -> throw new IllegalStateException("Cannot perform operation");
            };
            case Double i -> switch (second) {
                case Integer j -> i - j;
                case Double j -> i - j;
                default -> throw new IllegalStateException("Cannot perform operation");
            };
            default -> throw new IllegalStateException("Cannot perform operation");
        };
    }

    public static Object multiply(Object first, Object second) {
        return switch(first) {
            case Integer i -> switch (second) {
                case Integer j -> i * j;
                case Double j -> i * j;
                default -> throw new IllegalStateException("Cannot perform operation");
            };
            case Double i -> switch (second) {
                case Integer j -> i * j;
                case Double j -> i * j;
                default -> throw new IllegalStateException("Cannot perform operation");
            };
            default -> throw new IllegalStateException("Cannot perform operation");
        };
    }

    public static Object divide(Object first, Object second) {
        return switch(first) {
            case Integer i -> switch (second) {
                case Integer j -> i / j;
                case Double j -> i / j;
                default -> throw new IllegalStateException("Cannot perform operation");
            };
            case Double i -> switch (second) {
                case Integer j -> i / j;
                case Double j -> i / j;
                default -> throw new IllegalStateException("Cannot perform operation");
            };
            default -> throw new IllegalStateException("Cannot perform operation");
        };
    }
}
