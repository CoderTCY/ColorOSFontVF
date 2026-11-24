package io.github.colorosfontvf.hooks;

import android.content.Context;
import android.content.res.Configuration;
import android.os.SharedMemory;
import android.util.Log;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.libxposed.api.XposedInterface;
import io.github.colorosfontvf.font.CustomFontScanner;
import io.github.colorosfontvf.font.FontConfigPatcher;

/**
 * Installs system_server hooks that upgrade ColorOS FlipFont injection to native {@code FontConfig}
 * entries with full variable axes, multi-weight/style support, CJK fallback integration, world-readable
 * permission repair, and live {@code SharedMemory("fontMap")} rebuilds on Theme Store font changes.
 */
public final class SystemServerHooks {

    private static volatile WeakReference<Object> sFontManagerServiceRef;
    private static final ThreadLocal<Boolean> sPatchingConfig = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> sRefreshingFontMap = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private SystemServerHooks() {
    }

    /** Installs all system_server hooks for {@code FontManagerService}, {@code UpdatableFontDir}, {@code SystemFonts}, and {@code OplusFontUtils}. */
    public static void install(
            XposedInterface xp,
            ClassLoader systemClassLoader,
            List<XposedInterface.HookHandle> handles) {
        Class<?> fmsClass = HookUtils.findClass("com.android.server.graphics.fonts.FontManagerService", systemClassLoader);
        Class<?> updatableDirClass = HookUtils.findClass("com.android.server.graphics.fonts.UpdatableFontDir", systemClassLoader);
        Class<?> updatableDirExtClass = HookUtils.findClass("com.android.server.graphics.fonts.UpdatableFontDirExtImpl", systemClassLoader);
        Class<?> systemFontsClass = HookUtils.findClass("android.graphics.fonts.SystemFonts", systemClassLoader);
        Class<?> systemFontStaticExtClass = HookUtils.findClass("android.graphics.fonts.SystemFontExtImpl$StaticExtImpl", systemClassLoader);
        Class<?> fontConfigClass = HookUtils.findClass("android.text.FontConfig", systemClassLoader);
        Class<?> typefaceClass = HookUtils.findClass("android.graphics.Typeface", systemClassLoader);
        Class<?> typefaceExtClass = HookUtils.findClass("android.graphics.TypefaceExtImpl", systemClassLoader);
        Class<?> oplusFontUtilsClass = HookUtils.findClass(HookUtils.CLASS_OPLUS_FONT_UTILS, systemClassLoader);
        Class<?> oplusFontManagerClass = HookUtils.findClass("com.oplus.font.OplusFontManager", systemClassLoader);

        int hookedCount = 0;

        // 1. Capture FontManagerService instance to rebuild SharedMemory("fontMap") on runtime FlipFont switches.
        Constructor<?> fmsCtor = HookUtils.findConstructor(
                fmsClass, Context.class, boolean.class, CompletableFuture.class);
        if (HookUtils.hookExecutable(xp, handles, fmsCtor, chain -> {
            Object res = chain.proceed();
            sFontManagerServiceRef = new WeakReference<>(chain.getThisObject());
            return res;
        })) {
            hookedCount++;
        }

        // 2. Ensure custom font + companion files are world-readable (0644) before system_server validates/symlinks.
        Method relinkMethod = HookUtils.findMethod(oplusFontUtilsClass, "relinkDataFontToTarget", int.class, File.class);
        if (HookUtils.hookExecutable(xp, handles, relinkMethod, chain -> {
            int targetType = (Integer) chain.getArg(0);
            Object fileObj = chain.getArg(1);
            if (targetType == 2 && fileObj instanceof File) {
                File primary = (File) fileObj;
                if (primary.isFile()) {
                    FontConfigPatcher.ensureWorldReadable(xp, primary);
                    for (File f : CustomFontScanner.scan(primary).collectAllUniqueFiles()) {
                        FontConfigPatcher.ensureWorldReadable(xp, f);
                    }
                }
            }
            return chain.proceed();
        })) {
            hookedCount++;
        }

        // 3. Upgrade createCustomizeFontFamily() for any direct caller (e.g. SystemFontExtImpl).
        Method createCustomizeMethod = HookUtils.findMethod(oplusFontUtilsClass, "createCustomizeFontFamily");
        if (HookUtils.hookExecutable(xp, handles, createCustomizeMethod, chain -> {
            File primary = HookUtils.resolveCustomFontFile(xp, systemClassLoader);
            Object upgraded = FontConfigPatcher.buildIndividualNamedFamilyList(xp, primary);
            return upgraded != null ? upgraded : chain.proceed();
        })) {
            hookedCount++;
        }

        // 4. Bypass OplusFontUtils.createTypefaceForCustom() so Typeface.createFromFamilies() builds full families.
        Method createForCustomMethod = HookUtils.findMethod(
                oplusFontUtilsClass, "createTypefaceForCustom", Map.class, Map.Entry.class);
        if (HookUtils.hookExecutable(xp, handles, createForCustomMethod, chain -> Boolean.FALSE)) {
            hookedCount++;
        }

        // 5. Skip appending "individual-font" when native FontConfig patching succeeds, keeping apps off the 4-style clamp.
        Method appendIndividualMethod = HookUtils.findMethod(
                oplusFontUtilsClass, "apendIndividualFontFamily", List.class);
        if (HookUtils.hookExecutable(xp, handles, appendIndividualMethod, chain -> {
            File primary = HookUtils.resolveCustomFontFile(xp, systemClassLoader);
            if (isCustomFontActive(oplusFontUtilsClass, primary) && FontConfigPatcher.canPatch(xp, primary)) {
                return null;
            }
            return chain.proceed();
        })) {
            hookedCount++;
        }

        // 6. Patch UpdatableFontDir.getSystemFontConfig() and SystemFonts.getSystemPreinstalledFontConfig().
        // Wrap chain.proceed() inside sPatchingConfig too: getSystemFontConfig() internally calls
        // getSystemPreinstalledFontConfig(), so both hooked layers must not patch independently.
        XposedInterface.Hooker fontConfigHooker = chain -> {
            if (Boolean.TRUE.equals(sPatchingConfig.get())) {
                return chain.proceed();
            }
            sPatchingConfig.set(Boolean.TRUE);
            try {
                Object original = chain.proceed();
                if (original == null) {
                    return null;
                }
                File primary = HookUtils.resolveCustomFontFile(xp, systemClassLoader);
                if (!isCustomFontActive(oplusFontUtilsClass, primary)) {
                    return original;
                }
                return FontConfigPatcher.patchFontConfig(xp, original, primary);
            } finally {
                sPatchingConfig.set(Boolean.FALSE);
            }
        };
        if (HookUtils.hookExecutable(xp, handles, HookUtils.findMethod(updatableDirClass, "getSystemFontConfig"), fontConfigHooker)) {
            hookedCount++;
        }
        if (HookUtils.hookExecutable(xp, handles, HookUtils.findMethod(systemFontsClass, "getSystemPreinstalledFontConfig"), fontConfigHooker)) {
            hookedCount++;
        }

        // 7. Rebuild SharedMemory("fontMap") whenever Theme Store changes FlipFont at runtime.
        XposedInterface.Hooker flipFontChangeHooker = chain -> {
            int prevFlip = HookUtils.getStaticInt(oplusFontUtilsClass, "sFlipFont", -1);
            int prevUser = HookUtils.getStaticInt(oplusFontUtilsClass, "sUserId", 0);
            Object res = chain.proceed();
            int newFlip = HookUtils.getStaticInt(oplusFontUtilsClass, "sFlipFont", -1);
            int newUser = HookUtils.getStaticInt(oplusFontUtilsClass, "sUserId", 0);
            if (prevFlip != newFlip || prevUser != newUser) {
                refreshFontManagerService(xp, fmsClass);
            }
            return res;
        };
        if (HookUtils.hookExecutable(xp, handles, HookUtils.findMethod(oplusFontUtilsClass, "setFlipFont", Configuration.class), flipFontChangeHooker)) {
            hookedCount++;
        }
        if (HookUtils.hookExecutable(xp, handles, HookUtils.findMethod(oplusFontUtilsClass, "setFlipFontWhenUserChange", Configuration.class), flipFontChangeHooker)) {
            hookedCount++;
        }

        // Deoptimize AOT callers so hooks are never bypassed due to ART inlining.
        HookUtils.deoptimizeMethod(xp, fmsClass, "updateSerializedFontMap");
        HookUtils.deoptimizeMethod(xp, fmsClass, "getSystemFontConfig");
        HookUtils.deoptimizeMethod(xp, updatableDirExtClass, "apendIndividualFontFamily", List.class);
        if (fontConfigClass != null) {
            HookUtils.deoptimizeMethod(xp, systemFontStaticExtClass, "apendIndividualFontFamily", fontConfigClass);
        }
        HookUtils.deoptimizeMethod(xp, typefaceClass, "initSystemDefaultTypefaces", Map.class, List.class, Map.class);
        HookUtils.deoptimizeMethod(xp, typefaceExtClass, "createTypefaceForCustom", Map.class, Map.Entry.class);
        HookUtils.deoptimizeMethod(xp, oplusFontManagerClass, "setFlipFont", Configuration.class, int.class);
        HookUtils.deoptimizeMethod(xp, oplusFontManagerClass, "setFlipFontWhenUserChange", Configuration.class, int.class);

        HookUtils.log(xp, Log.INFO, "SystemServerHooks installed (" + hookedCount + " hooks active)");
    }

    private static boolean isCustomFontActive(Class<?> oplusFontUtilsClass, File primary) {
        return primary != null && primary.isFile()
                && (HookUtils.getStaticBoolean(oplusFontUtilsClass, "isFlipFontUsed", false) || primary.exists());
    }

    private static void refreshFontManagerService(XposedInterface xp, Class<?> fmsClass) {
        if (Boolean.TRUE.equals(sRefreshingFontMap.get())) {
            return;
        }
        WeakReference<Object> ref = sFontManagerServiceRef;
        Object fms = ref != null ? ref.get() : null;
        if (fms == null || fmsClass == null) {
            return;
        }
        sRefreshingFontMap.set(Boolean.TRUE);
        try {
            Field lockField = HookUtils.findField(fmsClass, "mUpdatableFontDirLock");
            Field dirField = HookUtils.findField(fmsClass, "mUpdatableFontDir");
            Object lock = lockField != null ? lockField.get(fms) : fms;
            if (lock == null) {
                lock = fms;
            }
            synchronized (lock) {
                Object updatableDir = dirField != null ? dirField.get(fms) : null;
                if (updatableDir != null) {
                    Method updateSerialized = HookUtils.findMethod(fmsClass, "updateSerializedFontMap");
                    if (updateSerialized != null) {
                        xp.getInvoker(updateSerialized).invoke(fms);
                        HookUtils.log(xp, Log.INFO, "Rebuilt FontManagerService SharedMemory fontMap after FlipFont change");
                    }
                } else {
                    Method serializeSystem = HookUtils.findMethod(fmsClass, "serializeSystemServerFontMap");
                    Method setSerialized = HookUtils.findMethod(fmsClass, "setSerializedFontMap", SharedMemory.class);
                    if (serializeSystem != null && setSerialized != null) {
                        Object mem = xp.getInvoker(serializeSystem).invoke(null);
                        xp.getInvoker(setSerialized).invoke(fms, mem);
                        HookUtils.log(xp, Log.INFO, "Rebuilt preinstalled SharedMemory fontMap after FlipFont change");
                    }
                }
            }
        } catch (Throwable t) {
            HookUtils.log(xp, Log.ERROR, "Failed to refresh FontManagerService SharedMemory", t);
        } finally {
            sRefreshingFontMap.set(Boolean.FALSE);
        }
    }
}
