package io.github.colorosfontvf;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.colorosfontvf.hooks.AppHooks;
import io.github.colorosfontvf.hooks.SettingsHooks;
import io.github.colorosfontvf.hooks.SystemServerHooks;

/**
 * Libxposed API 102 entry point for ColorOSFontVF (Vector 2.2+).
 */
public class ColorOSFontVfModule extends XposedModule {

    private static final String TAG = "ColorOSFontVF";
    private static final String PKG_SETTINGS = "com.android.settings";

    private final List<XposedInterface.HookHandle> mHookHandles =
            Collections.synchronizedList(new ArrayList<>());

    private volatile boolean mIsSystemServer;
    private volatile String mProcessName = "";

    public ColorOSFontVfModule() {
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        mIsSystemServer = param.isSystemServer();
        mProcessName = param.getProcessName();
        log(Log.INFO, null, "[ColorOSFontVF] Loaded in process=" + mProcessName
                + " systemServer=" + mIsSystemServer
                + " framework=" + getFrameworkName()
                + " " + getFrameworkVersion()
                + " (API " + getApiVersion() + ")");
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        SystemServerHooks.install(this, param.getClassLoader(), mHookHandles);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        ClassLoader cl = param.getClassLoader();
        if (PKG_SETTINGS.equals(pkg)) {
            SettingsHooks.install(this, cl, mHookHandles);
        }
        if (param.isFirstPackage() && !mIsSystemServer) {
            AppHooks.install(this, cl, pkg, mHookHandles);
        }
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        // No module-owned threads or old-classloader state retained; safe to hot-reload.
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        mIsSystemServer = param.isSystemServer();
        mProcessName = param.getProcessName();
        for (XposedInterface.HookHandle oldHandle : param.getOldHookHandles()) {
            oldHandle.unhook();
        }
        mHookHandles.clear();

        if (mIsSystemServer) {
            ClassLoader sysCl = resolveRuntimeClassLoader();
            SystemServerHooks.install(this, sysCl, mHookHandles);
        } else {
            ClassLoader appCl = resolveRuntimeClassLoader();
            if (PKG_SETTINGS.equals(mProcessName)) {
                SettingsHooks.install(this, appCl, mHookHandles);
            }
            AppHooks.install(this, appCl, mProcessName, mHookHandles);
        }
        log(Log.INFO, null, "[ColorOSFontVF] Hot-reloaded hooks in process=" + mProcessName);
    }

    private ClassLoader resolveRuntimeClassLoader() {
        if (!mIsSystemServer) {
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Method curAppMethod = atClass.getDeclaredMethod("currentApplication");
                Object app = curAppMethod.invoke(null);
                if (app != null) {
                    ClassLoader cl = app.getClass().getClassLoader();
                    if (cl != null) {
                        return cl;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        ClassLoader ctxCl = Thread.currentThread().getContextClassLoader();
        return ctxCl != null ? ctxCl : ColorOSFontVfModule.class.getClassLoader();
    }
}
