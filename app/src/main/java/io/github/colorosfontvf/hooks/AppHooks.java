package io.github.colorosfontvf.hooks;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.HardwareRenderer;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.colorosfontvf.font.CustomFontScanner;
import io.github.colorosfontvf.font.FontFamilyPlan;

/**
 * Optional app-process fallback hooks that preserve variable-font families and exact weights/italics
 * if a user explicitly scopes third-party apps or switches fonts live via Theme Store without rebooting.
 */
public final class AppHooks {

    private static final LruCache<Long, Typeface> sRuntimeStyleCache = new LruCache<>(64);
    private static volatile Typeface sLastBaseTypeface;

    private AppHooks() {
    }

    public static void install(
            XposedInterface xp,
            ClassLoader appClassLoader,
            String packageName,
            List<XposedInterface.HookHandle> handles) {
        Class<?> oplusFontUtilsClass = HookUtils.findClass(HookUtils.CLASS_OPLUS_FONT_UTILS, appClassLoader);
        Class<?> oplusFontCompatClass = HookUtils.findClass("oplus.content.res.OplusFontUtils", appClassLoader);
        Class<?> injectorClass = HookUtils.findClass("android.graphics.OplusTypefaceInjector", appClassLoader);
        Class<?> typefaceExtClass = HookUtils.findClass("android.graphics.TypefaceExtImpl", appClassLoader);
        Class<?> iTypefaceExtClass = HookUtils.findClass("android.graphics.ITypefaceExt", appClassLoader);
        if (oplusFontUtilsClass == null) {
            return;
        }

        Method canvasFreeCaches = HookUtils.findMethod(Canvas.class, "freeCaches");
        Method canvasFreeTextCaches = HookUtils.findMethod(Canvas.class, "freeTextLayoutCaches");
        Method hwTrimMemory = HookUtils.findMethod(HardwareRenderer.class, "trimMemory", int.class);
        Method hwTrimCaches = HookUtils.findMethod(HardwareRenderer.class, "trimCaches", int.class);

        // 1. Ensure createTypefaceForCustom never bypasses Typeface.createFromFamilies().
        Method createForCustom = HookUtils.findMethod(
                oplusFontUtilsClass, "createTypefaceForCustom", Map.class, Map.Entry.class);
        HookUtils.hookExecutable(xp, handles, createForCustom, chain -> Boolean.FALSE);

        // 2. Upgrade live Theme Store hot-switch typeface construction in running apps.
        Method doUpdate = HookUtils.findMethod(oplusFontUtilsClass, "doUpdateTypeface", Configuration.class);
        HookUtils.hookExecutable(xp, handles, doUpdate, chain -> {
            File primary = HookUtils.resolveCustomFontFile(xp, appClassLoader);
            if (!primary.isFile()) {
                return chain.proceed();
            }
            Typeface built = buildRuntimeTypeface(xp, primary);
            if (built == null) {
                return chain.proceed();
            }

            HookUtils.setStaticBoolean(oplusFontUtilsClass, "isFlipFontUsed", true);
            HookUtils.setStaticBoolean(oplusFontCompatClass, "isFlipFontUsed", true);
            HookUtils.setStaticObject(oplusFontUtilsClass, "sIndividualTypeface", built);

            Typeface[] fourStyles = new Typeface[]{
                    Typeface.create(built, Typeface.NORMAL),
                    Typeface.create(built, Typeface.BOLD),
                    Typeface.create(built, Typeface.ITALIC),
                    Typeface.create(built, Typeface.BOLD_ITALIC)
            };
            HookUtils.setStaticObject(oplusFontUtilsClass, "sCurrentTypefaces", fourStyles);
            HookUtils.setStaticObject(oplusFontUtilsClass, "sCurrentTypefacesArray",
                    new ArrayList<>(Arrays.asList(fourStyles)));

            synchronized (sRuntimeStyleCache) {
                sRuntimeStyleCache.evictAll();
                sLastBaseTypeface = built;
            }

            flushGraphicsCaches(xp, canvasFreeCaches, canvasFreeTextCaches, hwTrimMemory, hwTrimCaches);
            return null;
        });

        // 3. Preserve weight (1..1000) & italic when flipTypeface(ITypefaceExt) intercepts system typefaces.
        if (iTypefaceExtClass != null) {
            Method flipSingle = HookUtils.findMethod(oplusFontUtilsClass, "flipTypeface", iTypefaceExtClass);
            Method extGetTypeface = HookUtils.findMethod(iTypefaceExtClass, "getTypeface");
            Method extIsSystem = HookUtils.findMethod(iTypefaceExtClass, "isSystemTypeface");
            Method injectorIsSystem = HookUtils.findMethod(injectorClass, "isSystemTypeface", Typeface.class);

            HookUtils.hookExecutable(xp, handles, flipSingle, chain -> {
                if (!HookUtils.getStaticBoolean(oplusFontUtilsClass, "isFlipFontUsed", false)
                        || !HookUtils.getStaticBoolean(oplusFontUtilsClass, "sReplaceFont", true)) {
                    return chain.proceed();
                }
                Object individualObj = HookUtils.getStaticObject(oplusFontUtilsClass, "sIndividualTypeface");
                if (!(individualObj instanceof Typeface)) {
                    return chain.proceed();
                }
                Typeface base = (Typeface) individualObj;
                Object ext = chain.getArg(0);
                Typeface orig = (ext != null && extGetTypeface != null) ? (Typeface) extGetTypeface.invoke(ext) : null;
                boolean isSys = (ext != null && extIsSystem != null && Boolean.TRUE.equals(extIsSystem.invoke(ext)))
                        || (orig != null && injectorIsSystem != null && Boolean.TRUE.equals(injectorIsSystem.invoke(null, orig)));
                if (!isSys) {
                    return chain.proceed();
                }

                int weight = orig != null ? orig.getWeight() : 400;
                boolean italic = orig != null && orig.isItalic();
                long key = (((long) weight) << 1) | (italic ? 1L : 0L);
                synchronized (sRuntimeStyleCache) {
                    if (sLastBaseTypeface != base) {
                        sRuntimeStyleCache.evictAll();
                        sLastBaseTypeface = base;
                    }
                    Typeface cached = sRuntimeStyleCache.get(key);
                    if (cached != null) {
                        return cached;
                    }
                    Typeface styled = Typeface.create(base, weight, italic);
                    sRuntimeStyleCache.put(key, styled);
                    return styled;
                }
            });
        }

        HookUtils.deoptimizeMethod(xp, typefaceExtClass, "createTypefaceForCustom", Map.class, Map.Entry.class);
        HookUtils.deoptimizeMethod(xp, oplusFontUtilsClass, "updateThemeStoreFontConfig", Configuration.class, int.class);
        HookUtils.log(xp, Log.DEBUG, "AppHooks installed for " + packageName);
    }

    private static void flushGraphicsCaches(
            XposedInterface xp,
            Method canvasFreeCaches,
            Method canvasFreeTextCaches,
            Method hwTrimMemory,
            Method hwTrimCaches) {
        try {
            HookUtils.invokeStatic(xp, canvasFreeCaches);
            HookUtils.invokeStatic(xp, canvasFreeTextCaches);
            HookUtils.invokeStatic(xp, hwTrimMemory, 20);
            HookUtils.invokeStatic(xp, hwTrimCaches, 0);
        } catch (Throwable ignored) {
        }
    }

    private static Typeface buildRuntimeTypeface(XposedInterface xp, File primary) {
        try {
            CustomFontScanner.ScannedCustomFontSet set = CustomFontScanner.scan(primary, Locale.getDefault());
            if (set.isEmpty()) {
                return null;
            }
            FontFamilyPlan.FamilySpec spec = FontFamilyPlan.buildFamilySpec(set.sansFaces, "", "");
            if (spec.isEmpty()) {
                return null;
            }
            FontFamily.Builder builder = null;
            for (FontFamilyPlan.FontEntrySpec entry : spec.entries) {
                Font.Builder fb = new Font.Builder(entry.file)
                        .setTtcIndex(entry.ttcIndex)
                        .setWeight(entry.weight)
                        .setSlant(entry.slant);
                if (!entry.variationSettings.isEmpty()) {
                    fb.setFontVariationSettings(entry.variationSettings);
                }
                Font font = fb.build();
                if (builder == null) {
                    builder = new FontFamily.Builder(font);
                } else {
                    builder.addFont(font);
                }
            }
            if (builder == null) {
                return null;
            }

            FontFamily family = null;
            if (spec.expectedVarFamilyType != 0) {
                Method buildVar = HookUtils.findMethod(FontFamily.Builder.class, "buildVariableFamily");
                if (buildVar != null) {
                    family = (FontFamily) buildVar.invoke(builder);
                }
            }
            if (family == null) {
                family = builder.build();
            }

            return new Typeface.CustomFallbackBuilder(family)
                    .setSystemFallback("sans-serif")
                    .build();
        } catch (Throwable t) {
            HookUtils.log(xp, Log.WARN, "buildRuntimeTypeface failed for " + primary, t);
            return null;
        }
    }
}
