package com.operit.launcherblur;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.LauncherApps;
import android.util.Log;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 入口：只注入 Pixel Launcher。
 *
 * 时序模型：
 *   1) 用户在桌面点开任意 App -> Instrumentation.execStartActivity / LauncherApps.startMainActivity
 *      => 打开窗口背景模糊 + 桌面内容模糊（此刻启动动画正在进行，Launcher 窗口仍可见）
 *   2) Launcher 被 App 覆盖 -> callActivityOnStop => 停止后台轮询，保持状态（不可见，零开销）
 *   3) 回到桌面 -> callActivityOnResume => 先保持 hold 毫秒，再 VSync 同步淡出到清晰
 */
public class LauncherBlurModule implements IXposedHookLoadPackage {

    static final String TAG = "LauncherBlur";
    static final String PKG = "com.google.android.apps.nexuslauncher";
    static final boolean DBG = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !PKG.equals(lpparam.packageName)) {
            return;
        }
        Log.i(TAG, "module loaded, process=" + lpparam.processName);
        XposedBridge.log(TAG + ": loaded in " + lpparam.processName);
        try {
            hookStartActivity();
            hookLauncherApps();
            hookLifecycle();
            BlurEngine engine = BlurEngine.get();
            engine.installStateHook(lpparam.classLoader);
            engine.installDepthHook(lpparam.classLoader);
        } catch (Throwable t) {
            Log.e(TAG, "hook install failed", t);
            XposedBridge.log(t);
        }
    }

    private void hookStartActivity() {
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                Instrumentation.class, "execStartActivity", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity caller = firstActivity(param.args);
                        if (!isLauncherActivity(caller)) {
                            return;
                        }
                        try {
                            BlurEngine.get().onExternalLaunch(caller);
                        } catch (Throwable t) {
                            Log.e(TAG, "onExternalLaunch", t);
                        }
                    }
                });
        Log.i(TAG, "hook: Instrumentation.execStartActivity (" + (hooks == null ? 0 : hooks.size()) + ")");
    }

    private void hookLauncherApps() {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    LauncherApps.class, "startMainActivity", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                BlurEngine.get().onExternalLaunchFromCurrentActivity();
                            } catch (Throwable t) {
                                Log.e(TAG, "onExternalLaunch(LauncherApps)", t);
                            }
                        }
                    });
            Log.i(TAG, "hook: LauncherApps.startMainActivity (" + (hooks == null ? 0 : hooks.size()) + ")");
        } catch (Throwable t) {
            Log.w(TAG, "hookLauncherApps unavailable", t);
        }
    }

    private void hookLifecycle() {
        XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity a = firstActivity(param.args);
                if (!isLauncherActivity(a)) {
                    return;
                }
                try {
                    BlurEngine.get().onLauncherResumed(a);
                } catch (Throwable t) {
                    Log.e(TAG, "onLauncherResumed", t);
                }
            }
        });

        XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnStop", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity a = firstActivity(param.args);
                if (!isLauncherActivity(a)) {
                    return;
                }
                try {
                    BlurEngine.get().onLauncherStopped(a);
                } catch (Throwable t) {
                    Log.e(TAG, "onLauncherStopped", t);
                }
            }
        });
        Log.i(TAG, "hook: Instrumentation.callActivityOnResume / onStop");
    }

    private static Activity firstActivity(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object o : args) {
            if (o instanceof Activity) {
                return (Activity) o;
            }
        }
        return null;
    }

    static boolean isLauncherActivity(Activity a) {
        if (a == null) {
            return false;
        }
        try {
            String cls = a.getClass().getName();
            return cls != null && cls.startsWith(PKG + ".");
        } catch (Throwable t) {
            return false;
        }
    }
}
