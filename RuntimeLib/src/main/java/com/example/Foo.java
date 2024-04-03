package com.example;

public class Foo implements Baz  {
    public static Foo instance = new Foo();

    public void print(String item) {
        System.out.println(item);
    }

    public String foo(String y) {
        return y+ " + Foo";
    }
}
