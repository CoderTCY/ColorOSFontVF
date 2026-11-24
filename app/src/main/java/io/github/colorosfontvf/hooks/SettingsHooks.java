package io.github.colorosfontvf.hooks;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.colorosfontvf.font.FontConfigPatcher;
import io.github.colorosfontvf.font.OpenTypeInspector;

/**
 * Hooks ColorOS Settings so the native "Font Weight" variation slider stays unlocked whenever the
 * active custom font natively supports the OpenType {@code 'wght'} axis.
 */
public final class SettingsHooks {

    private static final String CLASS_FONT_VARIATION_UTILS =
            "com.oplus.settings.feature.display.font.FontVariationUtils";

    private SettingsHooks() {
    }

    public static void install(
            XposedInterface xp,
            ClassLoader settingsClassLoader,
            List<XposedInterface.HookHandle> handles) {
        Class<?> utilsClass = HookUtils.findClass(CLASS_FONT_VARIATION_UTILS, settingsClassLoader);
        if (utilsClass == null) {
            HookUtils.log(xp, Log.WARN, "FontVariationUtils not found in Settings");
            return;
        }

        Method isSupportFontWeight = HookUtils.findMethod(utilsClass, "isSupportFontWeight", Context.class);
        Method getSettings = HookUtils.findMethod(utilsClass, "getFontVariationSettings", Context.class);
        Method getStatus = HookUtils.findMethod(utilsClass, "getFontVariationStatus", int.class);
        Method isSupportVar = HookUtils.findMethod(utilsClass, "isSupportFontVariation", int.class);

        if (HookUtils.hookExecutable(xp, handles, isSupportFontWeight, chain -> {
            if (Boolean.TRUE.equals(chain.proceed())) {
                return Boolean.TRUE;
            }
            if (!supportsWghtAxis(xp, Typeface.DEFAULT)) {
                return Boolean.FALSE;
            }
            Context ctx = (Context) chain.getArg(0);
            if (ctx == null || getSettings == null || getStatus == null || isSupportVar == null) {
                return Boolean.FALSE;
            }
            int settingsVal = (Integer) HookUtils.invokeStatic(xp, getSettings, ctx);
            int status = (Integer) HookUtils.invokeStatic(xp, getStatus, settingsVal);
            return HookUtils.invokeStatic(xp, isSupportVar, status);
        })) {
            HookUtils.log(xp, Log.INFO, "SettingsHooks installed on FontVariationUtils.isSupportFontWeight");
        }
    }

    private static boolean supportsWghtAxis(XposedInterface xp, Typeface tf) {
        if (tf == null) {
            return false;
        }
        Paint probe = new Paint();
        probe.setTypeface(tf);
        if (probe.setFontVariationSettings("'wght' 450")) {
            return true;
        }
        return FontConfigPatcher.isTypefaceAxisSupported(xp, tf, OpenTypeInspector.TAG_WGHT);
    }
}
