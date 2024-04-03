package com.example;

public class Bar implements Baz{

    public static Bar instance = new Bar();

    public String foo(String y) {
        return y+ " + Bar";
    }
}
