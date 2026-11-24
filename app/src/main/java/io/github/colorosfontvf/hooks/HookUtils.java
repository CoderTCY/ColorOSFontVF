package io.github.colorosfontvf.hooks;

import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Shared reflection, hook-registration, and deoptimization utilities for libxposed API 102 hooks.
 */
final class HookUtils {

    static final String TAG = "ColorOSFontVF";
    static final String CLASS_OPLUS_FONT_UTILS = "com.oplus.util.OplusFontUtils";
    static final String DEFAULT_CUSTOM_FONT_PATH = "/data/format_unclear/font/Customized-Regular.ttf";
    static final String LEGACY_CUSTOM_FONT_PATH = "/data/system/font/Customized-Regular.ttf";

    private HookUtils() {
    }

    static Class<?> findClass(String className, ClassLoader primaryLoader) {
        if (primaryLoader != null) {
            try {
                return Class.forName(className, false, primaryLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        try {
            return Class.forName(className, false, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        if (cls == null) {
            return null;
        }
        try {
            Method m = cls.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static Constructor<?> findConstructor(Class<?> cls, Class<?>... paramTypes) {
        if (cls == null) {
            return null;
        }
        try {
            Constructor<?> c = cls.getDeclaredConstructor(paramTypes);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static Field findField(Class<?> cls, String name) {
        for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    static void log(XposedInterface xp, int priority, String msg) {
        if (xp != null) {
            xp.log(priority, null, "[ColorOSFontVF] " + msg);
        } else {
            Log.println(priority, TAG, msg);
        }
    }

    static void log(XposedInterface xp, int priority, String msg, Throwable tr) {
        if (xp != null) {
            xp.log(priority, null, "[ColorOSFontVF] " + msg, tr);
        } else {
            Log.println(priority, TAG, msg + "\n" + Log.getStackTraceString(tr));
        }
    }

    static boolean hookExecutable(
            XposedInterface xp,
            List<XposedInterface.HookHandle> handles,
            Executable target,
            XposedInterface.Hooker hooker) {
        if (xp == null || target == null) {
            return false;
        }
        try {
            XposedInterface.HookHandle handle = xp.hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            if (handles != null) {
                handles.add(handle);
            }
            return true;
        } catch (Throwable t) {
            log(xp, Log.ERROR, "Failed to hook " + target, t);
            return false;
        }
    }

    static void deoptimizeMethod(XposedInterface xp, Class<?> cls, String name, Class<?>... paramTypes) {
        Method m = findMethod(cls, name, paramTypes);
        if (m != null) {
            try {
                xp.deoptimize(m);
            } catch (Throwable t) {
                log(xp, Log.WARN, "deoptimize failed for " + cls.getName() + "." + name, t);
            }
        }
    }

    static Object invokeStatic(XposedInterface xp, Method method, Object... args) throws Throwable {
        if (method == null) {
            return null;
        }
        if (xp != null) {
            return xp.getInvoker(method).invoke(null, args);
        }
        return method.invoke(null, args);
    }

    static boolean getStaticBoolean(Class<?> cls, String fieldName, boolean fallback) {
        Field f = findField(cls, fieldName);
        if (f == null) {
            return fallback;
        }
        try {
            return f.getBoolean(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    static int getStaticInt(Class<?> cls, String fieldName, int fallback) {
        Field f = findField(cls, fieldName);
        if (f == null) {
            return fallback;
        }
        try {
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    static void setStaticBoolean(Class<?> cls, String fieldName, boolean value) {
        Field f = findField(cls, fieldName);
        if (f != null) {
            try {
                f.setBoolean(null, value);
            } catch (Throwable ignored) {
            }
        }
    }

    static Object getStaticObject(Class<?> cls, String fieldName) {
        Field f = findField(cls, fieldName);
        if (f == null) {
            return null;
        }
        try {
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void setStaticObject(Class<?> cls, String fieldName, Object value) {
        Field f = findField(cls, fieldName);
        if (f != null) {
            try {
                f.set(null, value);
            } catch (Throwable ignored) {
            }
        }
    }

    static File resolveCustomFontFile(XposedInterface xp, ClassLoader cl) {
        Class<?> oplusFontUtils = findClass(CLASS_OPLUS_FONT_UTILS, cl);
        Method getFile = findMethod(oplusFontUtils, "getCustomizedFontFile", boolean.class);
        if (getFile != null) {
            try {
                Object path = invokeStatic(xp, getFile, false);
                if (path instanceof String && !((String) path).isEmpty()) {
                    return new File((String) path);
                }
            } catch (Throwable ignored) {
            }
        }
        File fbe = new File(DEFAULT_CUSTOM_FONT_PATH);
        if (fbe.isFile()) {
            return fbe;
        }
        File legacy = new File(LEGACY_CUSTOM_FONT_PATH);
        return legacy.isFile() ? legacy : fbe;
    }
}
