package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static float getFloatField(Object obj, String fieldName) {
        return 0f;
    }

    public static int getIntField(Object obj, String fieldName) {
        return 0;
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return false;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static void setAdditionalInstanceField(Object obj, String key, Object value) {
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        return null;
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        return null;
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName,
                                                 Class<?>... parameterTypes) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }
}
