package com.language;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Calling {
    public static Object callDynamicDispatch(Object instance, String methodName, Object... args) {
        return dynamicDispatch(instance.getClass(), methodName, instance, args);
    }

    public static Object callStaticDynamicDispatch(String className, String methodName, Object... args) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className.replaceAll("/", "."));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return dynamicDispatch(clazz, methodName, null, args);
    }

    public static Object staticPropertyAccess(String className, String fieldName) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className.replaceAll("/", "."));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return propertyAccess(clazz, null, fieldName);
    }

    public static Object dynamicPropertyAccess(Object instance, String fieldName) {
        return propertyAccess(instance.getClass(), instance, fieldName);
    }

    private static Object propertyAccess(Class<?> clazz, Object instance, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            return field.get(instance);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object dynamicDispatch(Class<?> clazz, String methodName, Object instance, Object[] args) {
        List<Method> potentialMethods = Arrays.stream(clazz.getMethods())
                .filter(method -> method.getName().equals(methodName) && method.getParameterCount() == args.length)
                .toList();
        if (potentialMethods.isEmpty()) {
            throw new IllegalStateException("No method found with "+args.length+" arguments");
        }
        for (Method method : potentialMethods) {
            if (!checkMatchingParameter(method.getParameterTypes(), args)) {
                continue;
            }
            //match found
            try {
                return method.invoke(instance, args);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalStateException("No matching method found");
    }

    private static boolean checkMatchingParameter(Class<?>[] parameters, Object[] objects) {
        for (int i = 0; i < parameters.length; ++i) {
            if (!parameters[i].isInstance(objects[i])) {
                return false;
            }
        }
        return true;
    }
}
