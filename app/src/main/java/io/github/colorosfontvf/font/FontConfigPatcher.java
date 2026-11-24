package io.github.colorosfontvf.font;

import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontStyle;
import android.os.LocaleList;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * Converts planned custom font families into Android {@code android.text.FontConfig} objects via
 * hidden-API-safe reflection / libxposed invokers, with full validation, world-readable permission
 * repair (avoiding ColorOS {@code OplusOSUI-XThin} fallback), and named/CJK fallback injection.
 */
public final class FontConfigPatcher {

    private static final String TAG = "ColorOSFontVF";

    public static final String CUSTOM_FONT_FAMILY_NAME = "individual-font";

    private static final int VARIANT_DEFAULT = 0;

    private static final String NAME_SANS_SERIF = "sans-serif";
    private static final String NAME_SYS_SANS_EN = "sys-sans-en";
    private static final String NAME_OP_SANS_EN = "op-sans-en";
    private static final String NAME_SANS_SERIF_CONDENSED = "sans-serif-condensed";
    private static final String NAME_SYS_SERIF = "sys-serif";
    private static final String NAME_SERIF = "serif";
    private static final String NAME_MONOSPACE = "monospace";

    private static Constructor<?> sFontCtor;
    private static Constructor<?> sFamilyCtor;
    private static Constructor<?> sNamedListCtor;
    private static Constructor<?> sFontConfigCtor;

    private static Method sGetNamedFamilyLists;
    private static Method sGetFontFamilies;
    private static Method sGetAliases;
    private static Method sGetLocaleFallbackCustomizations;
    private static Method sGetLastModifiedTimeMillis;
    private static Method sGetConfigVersion;
    private static Method sNamedListGetName;
    private static Method sNamedListGetFamilies;
    private static Method sFamilyGetLocaleList;
    private static Method sFamilyGetFontList;
    private static Method sFontGetFile;
    private static Method sIsSupportedAxes;

    private static boolean sReflectionInitAttempted;

    private FontConfigPatcher() {
    }

    private static synchronized void ensureReflection() {
        if (sReflectionInitAttempted) {
            return;
        }
        sReflectionInitAttempted = true;
        try {
            Class<?> fontConfigCls = Class.forName("android.text.FontConfig");
            Class<?> fontCls = Class.forName("android.text.FontConfig$Font");
            Class<?> familyCls = Class.forName("android.text.FontConfig$FontFamily");
            Class<?> namedListCls = Class.forName("android.text.FontConfig$NamedFamilyList");

            sFontCtor = fontCls.getDeclaredConstructor(
                    File.class, File.class, String.class, FontStyle.class,
                    int.class, String.class, String.class, int.class
            );
            sFontCtor.setAccessible(true);

            sFamilyCtor = familyCls.getDeclaredConstructor(
                    List.class, LocaleList.class, int.class
            );
            sFamilyCtor.setAccessible(true);

            sNamedListCtor = namedListCls.getDeclaredConstructor(
                    List.class, String.class
            );
            sNamedListCtor.setAccessible(true);

            sFontConfigCtor = fontConfigCls.getDeclaredConstructor(
                    List.class, List.class, List.class, List.class, long.class, int.class
            );
            sFontConfigCtor.setAccessible(true);

            sGetNamedFamilyLists = fontConfigCls.getDeclaredMethod("getNamedFamilyLists");
            sGetFontFamilies = fontConfigCls.getDeclaredMethod("getFontFamilies");
            sGetAliases = fontConfigCls.getDeclaredMethod("getAliases");
            sGetLocaleFallbackCustomizations = fontConfigCls.getDeclaredMethod("getLocaleFallbackCustomizations");
            sGetLastModifiedTimeMillis = fontConfigCls.getDeclaredMethod("getLastModifiedTimeMillis");
            sGetConfigVersion = fontConfigCls.getDeclaredMethod("getConfigVersion");

            sNamedListGetName = namedListCls.getDeclaredMethod("getName");
            sNamedListGetFamilies = namedListCls.getDeclaredMethod("getFamilies");
            sFamilyGetLocaleList = familyCls.getDeclaredMethod("getLocaleList");

            sGetNamedFamilyLists.setAccessible(true);
            sGetFontFamilies.setAccessible(true);
            sGetAliases.setAccessible(true);
            sGetLocaleFallbackCustomizations.setAccessible(true);
            sGetLastModifiedTimeMillis.setAccessible(true);
            sGetConfigVersion.setAccessible(true);
            sNamedListGetName.setAccessible(true);
            sNamedListGetFamilies.setAccessible(true);
            sFamilyGetFontList = familyCls.getDeclaredMethod("getFontList");
            sFontGetFile = fontCls.getDeclaredMethod("getFile");
            sFamilyGetFontList.setAccessible(true);
            sFontGetFile.setAccessible(true);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to resolve FontConfig hidden APIs", t);
        }
    }

    /** Queries {@code Typeface.isSupportedAxes(int)} safely across SDK visibility boundaries. */
    public static boolean isTypefaceAxisSupported(XposedInterface xposed, Typeface typeface, int tagValue) {
        if (typeface == null) {
            return false;
        }
        try {
            if (sIsSupportedAxes == null) {
                Method m = Typeface.class.getDeclaredMethod("isSupportedAxes", int.class);
                m.setAccessible(true);
                sIsSupportedAxes = m;
            }
            Object result = invokeMethod(xposed, sIsSupportedAxes, typeface, tagValue);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object invokeConstructor(XposedInterface xposed, Constructor<?> ctor, Object... args)
            throws Exception {
        if (ctor == null) {
            throw new IllegalStateException("Missing FontConfig constructor");
        }
        if (xposed != null) {
            try {
                return xposed.getInvoker(ctor).newInstance(args);
            } catch (Throwable ignored) {
            }
        }
        return ctor.newInstance(args);
    }

    private static Object invokeMethod(XposedInterface xposed, Method method, Object receiver, Object... args)
            throws Exception {
        if (method == null) {
            throw new IllegalStateException("Missing FontConfig method");
        }
        if (xposed != null) {
            try {
                return xposed.getInvoker(method).invoke(receiver, args);
            } catch (Throwable ignored) {
            }
        }
        return method.invoke(receiver, args);
    }

    /**
     * Ensures {@code file} is world-readable ({@code o+r}) so unprivileged app processes can open it
     * inside {@code libhwui.so} instead of hitting the 17-glyph {@code OplusOSUI-XThin.ttf} fallback.
     */
    public static boolean ensureWorldReadable(XposedInterface xposed, File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String path = file.getAbsolutePath();
        try {
            StructStat stat = Os.stat(path);
            if ((stat.st_mode & 0004) != 0) {
                return true;
            }
            int fixedMode = (stat.st_mode & 0777) | 0044;
            try {
                Os.chmod(path, fixedMode);
            } catch (Throwable chmodErr) {
                log(xposed, Log.WARN, "chmod 0" + Integer.toOctalString(fixedMode) + " failed for " + path, chmodErr);
            }
            StructStat reStat = Os.stat(path);
            boolean ok = (reStat.st_mode & 0004) != 0;
            if (!ok) {
                log(xposed, Log.ERROR, "Refusing non-world-readable custom font: " + path
                        + " mode=0" + Integer.toOctalString(reStat.st_mode & 0777), null);
            }
            return ok;
        } catch (Throwable t) {
            log(xposed, Log.ERROR, "stat failed for custom font: " + path, t);
            return false;
        }
    }

    /** Returns {@code true} if {@code primaryFile} exists, is readable by all UIDs, and builds cleanly. */
    public static boolean canPatch(XposedInterface xposed, File primaryFile) {
        if (primaryFile == null || !primaryFile.isFile()) {
            return false;
        }
        if (!ensureWorldReadable(xposed, primaryFile)) {
            return false;
        }
        CustomFontScanner.ScannedCustomFontSet set = CustomFontScanner.scan(primaryFile, Locale.getDefault());
        if (set.isEmpty()) {
            return false;
        }
        FontFamilyPlan.FamilySpec sansSpec = FontFamilyPlan.buildFamilySpec(set.sansFaces, "", "");
        return !filterValidEntries(xposed, sansSpec.entries).isEmpty();
    }

    /**
     * Builds an upgraded {@code android.text.FontConfig$NamedFamilyList} named {@code "individual-font"}
     * containing full variable-font / multi-style declarations.
     */
    public static Object buildIndividualNamedFamilyList(XposedInterface xposed, File primaryFile) {
        if (primaryFile == null || !primaryFile.isFile()) {
            return null;
        }
        ensureReflection();
        CustomFontScanner.ScannedCustomFontSet set = CustomFontScanner.scan(primaryFile, Locale.getDefault());
        if (set.isEmpty()) {
            return null;
        }
        FontFamilyPlan.FamilySpec sansSpec = FontFamilyPlan.buildFamilySpec(set.sansFaces, "", "");
        Object family = buildFontFamily(xposed, sansSpec);
        if (family == null) {
            return null;
        }
        try {
            return invokeConstructor(xposed, sNamedListCtor,
                    Collections.singletonList(family), CUSTOM_FONT_FAMILY_NAME);
        } catch (Throwable t) {
            log(xposed, Log.ERROR, "Failed to construct NamedFamilyList for individual-font", t);
            return null;
        }
    }

    /**
     * Patches the system {@code FontConfig} so custom fonts prepend system sans families
     * (and optionally condensed, serif, mono, and CJK fallbacks) using Android's native pipeline,
     * while omitting {@code "individual-font"} so unhooked apps do not collapse to 4 static styles.
     */
    @SuppressWarnings("unchecked")
    public static Object patchFontConfig(XposedInterface xposed, Object original, File primaryFile) {
        if (original == null || primaryFile == null || !primaryFile.isFile()) {
            return original;
        }
        ensureReflection();
        if (sFontCtor == null || sFamilyCtor == null || sNamedListCtor == null || sFontConfigCtor == null) {
            return original;
        }

        CustomFontScanner.ScannedCustomFontSet set = CustomFontScanner.scan(primaryFile, Locale.getDefault());
        if (set.isEmpty()) {
            log(xposed, Log.WARN, "No valid SFNT faces found in " + primaryFile.getAbsolutePath(), null);
            return original;
        }

        for (File f : set.collectAllUniqueFiles()) {
            ensureWorldReadable(xposed, f);
        }

        FontFamilyPlan.FamilySpec sansSpec = FontFamilyPlan.buildFamilySpec(set.sansFaces, "", "");
        Object sansFamily = buildFontFamily(xposed, sansSpec);
        if (sansFamily == null) {
            log(xposed, Log.WARN, "Primary custom font failed Font.Builder validation; aborting patch", null);
            return original;
        }

        FontFamilyPlan.FamilySpec condensedSpec = FontFamilyPlan.buildCondensedSpec(set);
        Object condensedFamily = buildFontFamily(xposed, condensedSpec);
        if (condensedFamily == null) {
            condensedFamily = sansFamily;
        }

        Object serifFamily = !set.serifFaces.isEmpty()
                ? buildFontFamily(xposed, FontFamilyPlan.buildFamilySpec(set.serifFaces, "", ""))
                : null;
        Object monoFamily = !set.monoFaces.isEmpty()
                ? buildFontFamily(xposed, FontFamilyPlan.buildFamilySpec(set.monoFaces, "", ""))
                : null;
        Object hansFamily = !set.hansFaces.isEmpty()
                ? buildFontFamily(xposed, FontFamilyPlan.buildFamilySpec(set.hansFaces, "", "zh-Hans"))
                : null;
        Object hantFamily = !set.hantFaces.isEmpty()
                ? buildFontFamily(xposed, FontFamilyPlan.buildFamilySpec(set.hantFaces, "", "zh-Hant,zh-Bopo"))
                : null;

        try {
            List<Object> origNamed = (List<Object>) invokeMethod(xposed, sGetNamedFamilyLists, original);
            if (isAlreadyPatched(xposed, origNamed, primaryFile)) {
                return original;
            }
            List<Object> origFallbacks = (List<Object>) invokeMethod(xposed, sGetFontFamilies, original);
            List<Object> aliases = (List<Object>) invokeMethod(xposed, sGetAliases, original);
            List<Object> localeFallbacks = (List<Object>) invokeMethod(xposed, sGetLocaleFallbackCustomizations, original);
            long lastModified = (Long) invokeMethod(xposed, sGetLastModifiedTimeMillis, original);
            int configVersion = (Integer) invokeMethod(xposed, sGetConfigVersion, original);

            List<Object> newNamed = new ArrayList<>(origNamed.size());
            Set<String> seenNames = new HashSet<>();

            for (Object list : origNamed) {
                String name = (String) invokeMethod(xposed, sNamedListGetName, list);
                if (CUSTOM_FONT_FAMILY_NAME.equals(name)) {
                    // Omit "individual-font" so app processes keep isFlipFontUsed=false and use full VF/weight support.
                    continue;
                }
                seenNames.add(name);
                Object inject = selectFamilyForNamedTarget(
                        name, sansFamily, condensedFamily, serifFamily, monoFamily);
                if (inject != null) {
                    List<Object> existingFamilies = (List<Object>) invokeMethod(xposed, sNamedListGetFamilies, list);
                    List<Object> merged = new ArrayList<>(existingFamilies.size() + 1);
                    merged.add(inject);
                    merged.addAll(existingFamilies);
                    try {
                        newNamed.add(invokeConstructor(xposed, sNamedListCtor, merged, name));
                    } catch (Throwable t) {
                        log(xposed, Log.ERROR, "Failed to rebuild NamedFamilyList " + name, t);
                        newNamed.add(list);
                    }
                } else {
                    newNamed.add(list);
                }
            }

            ensureNamedFamilyPresent(xposed, newNamed, seenNames, NAME_SANS_SERIF, sansFamily);
            ensureNamedFamilyPresent(xposed, newNamed, seenNames, NAME_SYS_SANS_EN, sansFamily);
            ensureNamedFamilyPresent(xposed, newNamed, seenNames, NAME_OP_SANS_EN, sansFamily);

            List<Object> newFallbacks = new ArrayList<>(origFallbacks.size() + 2);
            boolean hansInserted = false;
            boolean hantInserted = false;
            for (Object fb : origFallbacks) {
                LocaleList ll = (LocaleList) invokeMethod(xposed, sFamilyGetLocaleList, fb);
                String tags = ll != null ? ll.toLanguageTags() : "";
                if (!hansInserted && hansFamily != null && tags.contains("zh-Hans")) {
                    newFallbacks.add(hansFamily);
                    hansInserted = true;
                }
                if (!hantInserted && hantFamily != null && tags.contains("zh-Hant")) {
                    newFallbacks.add(hantFamily);
                    hantInserted = true;
                }
                newFallbacks.add(fb);
            }
            if (!hansInserted && hansFamily != null) {
                newFallbacks.add(0, hansFamily);
            }
            if (!hantInserted && hantFamily != null) {
                newFallbacks.add(0, hantFamily);
            }

            Object patched = invokeConstructor(
                    xposed,
                    sFontConfigCtor,
                    newFallbacks,
                    aliases,
                    newNamed,
                    localeFallbacks,
                    lastModified,
                    configVersion
            );
            logSummary(xposed, set, sansSpec, condensedSpec, serifFamily != null, monoFamily != null,
                    hansFamily != null, hantFamily != null);
            return patched;
        } catch (Throwable t) {
            log(xposed, Log.ERROR, "Failed to patch FontConfig; returning original", t);
            return original;
        }
    }
    @SuppressWarnings("unchecked")
    private static boolean isAlreadyPatched(
            XposedInterface xposed, List<Object> namedLists, File primaryFile) {
        if (namedLists == null || primaryFile == null || sFamilyGetFontList == null || sFontGetFile == null) {
            return false;
        }
        try {
            for (Object list : namedLists) {
                String name = (String) invokeMethod(xposed, sNamedListGetName, list);
                if (!NAME_SANS_SERIF.equals(name)) {
                    continue;
                }
                List<Object> families = (List<Object>) invokeMethod(xposed, sNamedListGetFamilies, list);
                if (families == null || families.isEmpty()) {
                    return false;
                }
                List<Object> fonts = (List<Object>) invokeMethod(xposed, sFamilyGetFontList, families.get(0));
                if (fonts != null) {
                    for (Object f : fonts) {
                        File file = (File) invokeMethod(xposed, sFontGetFile, f);
                        if (primaryFile.equals(file)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void ensureNamedFamilyPresent(
            XposedInterface xposed,
            List<Object> list,
            Set<String> seenNames,
            String name,
            Object family) {
        if (seenNames.contains(name) || family == null) {
            return;
        }
        try {
            list.add(invokeConstructor(xposed, sNamedListCtor, Collections.singletonList(family), name));
            seenNames.add(name);
        } catch (Throwable t) {
            log(xposed, Log.WARN, "Could not add missing NamedFamilyList " + name, t);
        }
    }

    private static Object selectFamilyForNamedTarget(
            String name,
            Object sansFamily,
            Object condensedFamily,
            Object serifFamily,
            Object monoFamily) {
        if (name == null) {
            return null;
        }
        switch (name) {
            case NAME_SANS_SERIF:
            case NAME_SYS_SANS_EN:
            case NAME_OP_SANS_EN:
                return sansFamily;
            case NAME_SYS_SERIF:
                return serifFamily != null ? serifFamily : sansFamily;
            case NAME_SANS_SERIF_CONDENSED:
                return condensedFamily;
            case NAME_SERIF:
                return serifFamily;
            case NAME_MONOSPACE:
                return monoFamily;
            default:
                return null;
        }
    }

    private static Object buildFontFamily(XposedInterface xposed, FontFamilyPlan.FamilySpec spec) {
        if (spec == null || spec.isEmpty()) {
            return null;
        }
        List<FontFamilyPlan.FontEntrySpec> validEntries = filterValidEntries(xposed, spec.entries);
        if (validEntries.isEmpty()) {
            return null;
        }

        boolean useVar = spec.expectedVarFamilyType != 0;
        if (spec.expectedVarFamilyType == 3 && validEntries.size() != 2) {
            useVar = validEntries.size() == 1
                    && (validEntries.get(0).varTypeAxes & FontFamilyPlan.VAR_TYPE_AXES_WGHT) != 0;
        }

        List<Object> fontList = new ArrayList<>(validEntries.size());
        for (FontFamilyPlan.FontEntrySpec entry : validEntries) {
            try {
                FontStyle style = new FontStyle(entry.weight, entry.slant);
                int varAxes = useVar ? entry.varTypeAxes : FontFamilyPlan.VAR_TYPE_AXES_NONE;
                Object font = invokeConstructor(
                        xposed,
                        sFontCtor,
                        entry.file,
                        null,
                        entry.postScriptName,
                        style,
                        entry.ttcIndex,
                        entry.variationSettings,
                        null,
                        varAxes
                );
                fontList.add(font);
            } catch (Throwable t) {
                log(xposed, Log.ERROR, "Failed to construct FontConfig.Font for " + entry.file, t);
            }
        }
        if (fontList.isEmpty()) {
            return null;
        }

        LocaleList localeList = (spec.languageTags == null || spec.languageTags.isEmpty())
                ? LocaleList.getEmptyLocaleList()
                : LocaleList.forLanguageTags(spec.languageTags);
        try {
            return invokeConstructor(xposed, sFamilyCtor, fontList, localeList, VARIANT_DEFAULT);
        } catch (Throwable t) {
            log(xposed, Log.ERROR, "Failed to construct FontConfig.FontFamily", t);
            return null;
        }
    }

    private static List<FontFamilyPlan.FontEntrySpec> filterValidEntries(
            XposedInterface xposed, List<FontFamilyPlan.FontEntrySpec> entries) {
        List<FontFamilyPlan.FontEntrySpec> valid = new ArrayList<>(entries.size());
        for (FontFamilyPlan.FontEntrySpec entry : entries) {
            if (!ensureWorldReadable(xposed, entry.file)) {
                continue;
            }
            try {
                Font.Builder builder = new Font.Builder(entry.file)
                        .setTtcIndex(entry.ttcIndex)
                        .setWeight(entry.weight)
                        .setSlant(entry.slant);
                if (entry.variationSettings != null && !entry.variationSettings.isEmpty()) {
                    builder.setFontVariationSettings(entry.variationSettings);
                }
                builder.build();
                valid.add(entry);
            } catch (Throwable t) {
                log(xposed, Log.WARN, "Skipping invalid custom font entry "
                        + entry.file.getName() + "#" + entry.ttcIndex, t);
            }
        }
        return valid;
    }

    private static void logSummary(
            XposedInterface xposed,
            CustomFontScanner.ScannedCustomFontSet set,
            FontFamilyPlan.FamilySpec sansSpec,
            FontFamilyPlan.FamilySpec condensedSpec,
            boolean hasSerif,
            boolean hasMono,
            boolean hasHans,
            boolean hasHant) {
        OpenTypeInspector.FontFaceInfo primary = set.sansFaces.get(0);
        String msg = String.format(
                Locale.ROOT,
                "Patched FontConfig: primary=%s#%d (PS=%s, axes=%s, GSUB=%s, GPOS=%s), "
                        + "sansEntries=%d (varFamilyType=%d), condensedVar='%s', "
                        + "companions[serif=%s, mono=%s], fallbacks[zh-Hans=%s, zh-Hant=%s]",
                primary.file.getName(),
                primary.ttcIndex,
                primary.postScriptName,
                primary.axes,
                primary.hasGsub,
                primary.hasGpos,
                sansSpec.entries.size(),
                sansSpec.expectedVarFamilyType,
                condensedSpec.entries.isEmpty() ? "" : condensedSpec.entries.get(0).variationSettings,
                hasSerif,
                hasMono,
                hasHans,
                hasHant
        );
        log(xposed, Log.INFO, msg, null);
    }

    private static void log(XposedInterface xposed, int priority, String msg, Throwable tr) {
        String tagged = "[ColorOSFontVF] " + msg;
        if (xposed != null) {
            if (tr != null) {
                xposed.log(priority, null, tagged, tr);
            } else {
                xposed.log(priority, null, tagged);
            }
        } else {
            if (tr != null) {
                Log.println(priority, TAG, tagged + "\n" + Log.getStackTraceString(tr));
            } else {
                Log.println(priority, TAG, tagged);
            }
        }
    }
}
