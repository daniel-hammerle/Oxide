package com;

import java.util.Random;

public class Example {
    public static int test()throws IllegalArgumentException {
        if (new Random().nextBoolean())  {
            throw new IllegalArgumentException("Test");
        }

        return 32;
    }
}
