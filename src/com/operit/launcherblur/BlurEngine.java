package com.operit.launcherblur;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 真正的"实时"背景模糊：
 * 直接对 Launcher 窗口自己的 SurfaceControl 设置 backgroundBlurRadius，
 * 由 SurfaceFlinger 实时模糊窗口背后的壁纸层。
 */
public final class BlurEngine {

    private static final String TAG = LauncherBlurModule.TAG;
    private static final String FORCE_KEY = "launcherblur_force";
    private static final String TEST_KEY = "launcherblur_test_stuck";
    private static final long POLL_INTERVAL_MS = 500L;

    private static BlurEngine sInst;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> mActRef = new WeakReference<>(null);
    private WeakReference<View> mDecorRef = new WeakReference<>(null);
    private WeakReference<View> mWorkspaceRef = new WeakReference<>(null);
    private WeakReference<View> mHotseatRef = new WeakReference<>(null);
    private Context mAppCtx;
    private SurfaceControl mSc;
    private int mCurContent = -1;
    private static final View[] NO_VIEWS = new View[0];
    private Prefs mPrefs = new Prefs();
    private Object mNormalState;
    private Object mOverviewState;
    private boolean mStateRefsReady;
    private boolean mStateHookInstalled;
    private boolean mDepthHookInstalled;
    private boolean mPolling;
    private boolean mPollErrLogged;
    private String mLastStateName;

    /** 当前已应用的背景模糊半径，-1 表示未知（新 surface 视为 0） */
    private int mCur = -1;
    /** 动画代际，用来取消旧动画及表面重试 */
    private int mGen;
    private int mRetryLeft;
    private int mRetryRadius;
    private boolean mRetryBgOnly;

    /** 已经历过一次"从桌面启动 App"，回到桌面时才做淡出 */
    private boolean mArmed;
    /** 当前处于 Overview 需保持背景模糊 */
    private boolean mInOverviewBlur;
    private int mDesiredBgRadius;
    private boolean mPendingClearBg;
    private boolean mInDepthReassert;

    /** 图标层独立 generation：壁纸动画不得打断图标淡出 */
    private int mContentGen;
    /** 图标层当前是否真的挂着 RenderEffect */
    private boolean mContentApplied;
    /** 进入 armed 的 uptime */
    private long mArmedAt;
    /** 最近一次触发启动模糊时间（用于与 LauncherApps 钩子去重） */
    private long mLastLaunchTs;
    /** 图标层最后一次变化时间 */
    private long mContentAt;
    /** 背景模糊最后一次变化时间 */
    private long mBgAt;
    /** launcherblur_force 调试预览中：看门狗不得干预 */
    private boolean mForced;
    /** 看门狗：从「桌面已获得焦点且仍是模糊」开始计数 */
    private long mLingerFrom;
    /** 图标淡出的截止时间，此之前看门狗不许插手中断淡出 */
    private long mFadeDeadline;
    /** 每次启动 App 递增，用于作废上一轮的回桌面收尾 */
    private int mEpoch;
    private int mApplyEpoch;
    private int mTries;
    private boolean mStopped;
    private boolean mObserved;
    private boolean mApiFailed;
    private boolean mAttachLogged;

    private int mLaunchTarget;
    private int mOverviewTarget;

    private ValueAnimator mBgAnim;
    private ValueAnimator mContentAnim;

    private Constructor<?> mTxCtor;
    private Method mSetBlur;
    private Method mApply;
    private Method mClose;
    private Method mVriGetSc;

    public static synchronized BlurEngine get() {
        if (sInst == null) {
            sInst = new BlurEngine();
        }
        return sInst;
    }

    private BlurEngine() {}

    // ======================================================================
    // 外部事件
    // ======================================================================

    public void onExternalLaunchFromCurrentActivity() {
        Activity a = mActRef.get();
        if (a == null || mStopped || a.isFinishing() || a.isDestroyed()) {
            return;
        }
        onExternalLaunch(a);
    }

    /** 在桌面上启动了别的 App */
    public void onExternalLaunch(Activity act) {
        attach(act);
        Prefs p = mPrefs;
        if (p.enabled == 0) {
            d("launch ignored: disabled");
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (mArmed && now - mLastLaunchTs < 150L) {
            return;
        }
        mLastLaunchTs = now;
        mArmed = true;
        mInOverviewBlur = false;
        mPendingClearBg = false;
        mStopped = false;
        mArmedAt = now;
        mLingerFrom = 0L;
        mFadeDeadline = 0L;
        mEpoch++;
        cancelPendingTasks();
        mMain.postDelayed(mGuard, 1500L);
        mDesiredBgRadius = p.radius;
        animateTo(p.radius, p.fadeIn, false);
        Log.i(TAG, "launch -> blur " + p.radius + " (" + p.fadeIn + "ms)");
        mLaunchTarget = p.radius;
        mMain.postDelayed(mLaunchReassert1, 140L);
        mMain.postDelayed(mLaunchReassert2, 320L);
    }

    /** Launcher 回到前台 */
    public void onLauncherResumed(Activity act) {
        mStopped = false;
        attach(act);
        ensurePolling();
        Prefs p = mPrefs;
        if (p.noParallax != 0) {
            mLastAppliedNoParallax = 1;
            applyFixedWallpaperOffset(null, act);
        } else if (mLastAppliedNoParallax != 0) {
            mLastAppliedNoParallax = 0;
            syncWallpaperInterpolator();
        }
        if (p.enabled == 0) {
            mArmed = false;
            mInOverviewBlur = false;
            mPendingClearBg = false;
            cancelPendingTasks();
            clearContentNow();
            if (mCur > 0 || mCur < 0) {
                setRadius(0);
            }
            return;
        }
        if (mPendingClearBg && !mArmed && !mInOverviewBlur && !mForced) {
            mPendingClearBg = false;
            mCur = -1;
            setRadius(0);
        }
        if (!mArmed) {
            d("resume: not armed, nothing to do");
            scheduleSettle(400L);
            return;
        }
        mMain.removeCallbacks(mGuard);
        mMain.removeCallbacks(mFadeOut);
        mMain.removeCallbacks(mApplyRun);
        mMain.removeCallbacks(mLaunchReassert1);
        mMain.removeCallbacks(mLaunchReassert2);
        mMain.removeCallbacks(mOverviewApplyRun);
        mMain.removeCallbacks(mOverviewReassertRun);
        mTries = 0;
        mApplyEpoch = mEpoch;
        mLingerFrom = 0L;
        mMain.post(mApplyRun);
    }

    /** Launcher 被覆盖：停止后台轮询，保持状态（不可见，零开销） */
    public void onLauncherStopped(Activity act) {
        mStopped = true;
        mMain.removeCallbacks(mGuard);
        mMain.removeCallbacks(mLaunchReassert1);
        mMain.removeCallbacks(mLaunchReassert2);
        mMain.removeCallbacks(mOverviewApplyRun);
        mMain.removeCallbacks(mOverviewReassertRun);
        stopPolling();
    }

    // ======================================================================
    // 定时器 & 可取消任务
    // ======================================================================

    private void cancelPendingTasks() {
        mMain.removeCallbacks(mSettle);
        mMain.removeCallbacks(mGuard);
        mMain.removeCallbacks(mFadeOut);
        mMain.removeCallbacks(mApplyRun);
        mMain.removeCallbacks(mLaunchReassert1);
        mMain.removeCallbacks(mLaunchReassert2);
        mMain.removeCallbacks(mOverviewApplyRun);
        mMain.removeCallbacks(mOverviewReassertRun);
        mMain.removeCallbacks(mRetryRadiusRun);
    }

    private final Runnable mLaunchReassert1 = new Runnable() {
        @Override
        public void run() {
            if (!mArmed || mStopped || mPrefs.enabled == 0) {
                return;
            }
            reassertContent(mLaunchTarget);
        }
    };

    private final Runnable mLaunchReassert2 = new Runnable() {
        @Override
        public void run() {
            if (!mArmed || mStopped || mPrefs.enabled == 0) {
                return;
            }
            reassertContent(mLaunchTarget);
        }
    };

    private final Runnable mOverviewApplyRun = new Runnable() {
        @Override
        public void run() {
            if (mStopped || mForced || mPrefs.enabled == 0 || mPrefs.overview == 0) {
                return;
            }
            if (!isInOverviewNow()) {
                return;
            }
            mInOverviewBlur = true;
            mDesiredBgRadius = mOverviewTarget;
            mCur = -1;
            animateTo(mOverviewTarget, 220, true);
        }
    };

    private final Runnable mOverviewReassertRun = new Runnable() {
        @Override
        public void run() {
            if (mStopped || mForced || mPrefs.enabled == 0 || mPrefs.overview == 0) {
                return;
            }
            if (!isInOverviewNow()) {
                return;
            }
            mInOverviewBlur = true;
            mDesiredBgRadius = mOverviewTarget;
            reassertRadius(mOverviewTarget);
        }
    };

    private final Runnable mRetryRadiusRun = new Runnable() {
        @Override
        public void run() {
            attemptSetRadius(mRetryRadius, mGen, mRetryBgOnly);
        }
    };

    private final Runnable mApplyRun = new Runnable() {
        @Override
        public void run() {
            if (!mArmed || mApplyEpoch != mEpoch) {
                return;
            }
            if (!isDesktopNormalNow()) {
                if (mTries < 15) {
                    mTries++;
                    mMain.postDelayed(this, 80L);
                } else {
                    Log.w(TAG, "apply: state never settled to NORMAL -> hand over");
                    mArmed = false;
                    scheduleSettle(0L);
                }
                return;
            }
            Prefs p = mPrefs;
            boolean surfaceAlive = mSc != null && isValid(mSc);
            if (p.resumeBg == 1) {
                mCur = -1;
                mDesiredBgRadius = p.radius;
                animateTo(p.radius, 90, false);
            } else {
                mDesiredBgRadius = 0;
                if (!surfaceAlive) {
                    // 表面已被 WMS 释放重建，新表面初始半径即为 0
                    mCur = 0;
                }
                mBgAt = SystemClock.uptimeMillis();
            }
            mMain.removeCallbacks(mFadeOut);
            mMain.postDelayed(mFadeOut, Math.max(0, p.hold));
            Log.i(TAG, "resume -> restore blur " + p.radius + ", hold " + p.hold
                    + "ms then fade " + p.fadeOut + "ms");
        }
    };

    /** 兜底：启动 App 1.5s 后 Launcher 仍然可见，说明这次启动没真正离开桌面，撤销模糊 */
    private final Runnable mGuard = new Runnable() {
        @Override
        public void run() {
            Activity a = mActRef.get();
            if (a == null) {
                return;
            }
            boolean visible;
            try {
                visible = !mStopped && a.hasWindowFocus();
            } catch (Throwable t) {
                visible = false;
            }
            if (visible) {
                Log.w(TAG, "guard: launcher still visible -> revert blur");
                mArmed = false;
                mInOverviewBlur = false;
                mDesiredBgRadius = 0;
                animateTo(0, 150, false);
            }
        }
    };

    private final Runnable mFadeOut = new Runnable() {
        @Override
        public void run() {
            if (!mArmed) {
                return;
            }
            mArmed = false;
            mInOverviewBlur = false;
            mDesiredBgRadius = 0;
            mFadeDeadline = SystemClock.uptimeMillis() + mPrefs.fadeOut + 250L;
            if (mPrefs.resumeBg == 1) {
                animateTo(0, mPrefs.fadeOut, false);
            } else {
                animateContentTo(0, mPrefs.fadeOut);
                if (mCur > 0) {
                    animateTo(0, mPrefs.fadeOut, true);
                } else {
                    mCur = 0;
                }
            }
            scheduleSettle(mPrefs.fadeOut + 150L);
        }
    };

    private final Runnable mSettle = new Runnable() {
        @Override
        public void run() {
            Activity a = mActRef.get();
            if (a == null || mArmed || mInOverviewBlur || mForced) {
                return;
            }
            if (!isDesktopNormalNow()) {
                return;
            }
            if (mCurContent != 0 || mContentApplied) {
                Log.w(TAG, "settle: stale content blur " + mCurContent + " -> force clear");
            }
            clearContentNow();
            if (mCur != 0) {
                mCur = -1;
                setRadius(0);
            }
        }
    };

    private void scheduleSettle(long delay) {
        mMain.removeCallbacks(mSettle);
        mMain.postDelayed(mSettle, Math.max(0L, delay));
    }

    /** 无条件摘掉图标层 RenderEffect */
    private void clearContentNow() {
        boolean had = mContentApplied || mCurContent != 0;
        cancelContentAnim();
        for (View v : contentTargets()) {
            try {
                v.setRenderEffect(null);
                v.invalidate();
            } catch (Throwable ignore) {
            }
        }
        if (had) {
            Log.i(TAG, "content blur cleared");
        }
        mContentApplied = false;
        mCurContent = 0;
        mContentAt = SystemClock.uptimeMillis();
    }

    private void cancelBgAnim() {
        if (mBgAnim != null) {
            ValueAnimator anim = mBgAnim;
            mBgAnim = null;
            try {
                anim.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    private void cancelContentAnim() {
        if (mContentAnim != null) {
            ValueAnimator anim = mContentAnim;
            mContentAnim = null;
            try {
                anim.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    private void animateTo(int to, int durationMs) {
        animateTo(to, durationMs, false);
    }

    /**
     * VSync 同步动画（ValueAnimator），取代固定 6ms/12ms 定时器。
     */
    private void animateTo(final int to, int durationMs, final boolean bgOnly) {
        cancelBgAnim();
        final int gen = ++mGen;
        mMain.removeCallbacks(mRetryRadiusRun);
        final int from = mCur < 0 ? 0 : mCur;
        if (to == from) {
            if (!bgOnly) {
                applyContentBlur(to);
            }
            return;
        }
        if (durationMs <= 0) {
            setRadius(to, bgOnly);
            return;
        }
        final double exp = to < from ? 3.0 : 2.0;
        ValueAnimator anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(durationMs);
        anim.setInterpolator(new TimeInterpolator() {
            @Override
            public float getInterpolation(float t) {
                return (float) (1.0 - Math.pow(1.0 - t, exp));
            }
        });
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (gen != mGen) {
                    return;
                }
                int r = (Integer) a.getAnimatedValue();
                if (!bgOnly) {
                    applyContentBlur(r);
                }
                if (r != mCur) {
                    trySetRadius(r);
                }
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean mCanceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mBgAnim == animation) {
                    mBgAnim = null;
                }
                if (!mCanceled && gen == mGen) {
                    setRadius(to, bgOnly);
                }
            }
        });
        mBgAnim = anim;
        anim.start();
    }

    private void animateContentTo(final int to, int durationMs) {
        cancelContentAnim();
        final int gen = ++mContentGen;
        final int from = mCurContent < 0 ? 0 : mCurContent;
        if (to == from || durationMs <= 0) {
            applyContentBlur(to);
            return;
        }
        final double exp = to < from ? 3.0 : 2.0;
        ValueAnimator anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(durationMs);
        anim.setInterpolator(new TimeInterpolator() {
            @Override
            public float getInterpolation(float t) {
                return (float) (1.0 - Math.pow(1.0 - t, exp));
            }
        });
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (gen != mContentGen) {
                    return;
                }
                int r = (Integer) a.getAnimatedValue();
                applyContentBlur(r);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean mCanceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mContentAnim == animation) {
                    mContentAnim = null;
                }
                if (!mCanceled && gen == mContentGen) {
                    applyContentBlur(to);
                }
            }
        });
        mContentAnim = anim;
        anim.start();
    }

    private void setRadius(int r) {
        setRadius(r, false);
    }

    private void setRadius(int r, boolean bgOnly) {
        final int gen = mGen;
        mRetryLeft = 5;
        attemptSetRadius(r, gen, bgOnly);
    }

    private void attemptSetRadius(final int r, final int gen, final boolean bgOnly) {
        if (gen != mGen) {
            return;
        }
        if (r == mCur) {
            return;
        }
        if (!bgOnly) {
            applyContentBlur(r);
        }
        if (trySetRadius(r)) {
            if (r == 0) {
                mPendingClearBg = false;
            }
            return;
        }
        if (mRetryLeft-- <= 0) {
            Log.w(TAG, "give up setting blur radius " + r + " (surface unavailable)");
            if (r == 0) {
                mPendingClearBg = true;
            }
            return;
        }
        mRetryRadius = r;
        mRetryBgOnly = bgOnly;
        mMain.removeCallbacks(mRetryRadiusRun);
        mMain.postDelayed(mRetryRadiusRun, 50L);
    }

    private boolean trySetRadius(int r) {
        if (!ensureApi()) {
            return false;
        }
        SurfaceControl sc = mSc;
        if (sc == null || !isValid(sc)) {
            Activity a = mActRef.get();
            SurfaceControl fresh = a == null ? null : resolveSurface(a);
            if (fresh == null || !isValid(fresh)) {
                return false;
            }
            mSc = sc = fresh;
            d("surface re-resolved = " + fresh);
        }
        Object tx = null;
        try {
            int val = Math.max(0, r);
            tx = mTxCtor.newInstance();
            mSetBlur.invoke(tx, sc, val);
            mApply.invoke(tx);
            mCur = val;
            mBgAt = SystemClock.uptimeMillis();
            d("background blur radius = " + val);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setRadius(" + r + ") failed: " + t);
            mSc = null;
            return false;
        } finally {
            if (tx != null && mClose != null) {
                try {
                    mClose.invoke(tx);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 桌面内容（图标/小部件/搜索栏）模糊：
     * 若自定义 contentRadius > 0，则按 r / radius 比例缩放以保留渐变过渡。
     */
    private void applyContentBlur(int r) {
        View[] targets = contentTargets();
        if (targets.length == 0) {
            return;
        }
        int target = (mPrefs.content == 1) ? Math.max(0, r) : 0;
        if (target == mCurContent && ((target > 0) == mContentApplied)) {
            return;
        }
        int cr;
        if (target <= 0) {
            cr = 0;
        } else if (mPrefs.contentRadius > 0) {
            int base = Math.max(1, mPrefs.radius);
            cr = Math.max(1, Math.min(mPrefs.contentRadius,
                    Math.round((float) target * mPrefs.contentRadius / base)));
        } else {
            cr = target;
        }
        try {
            RenderEffect effect = null;
            if (cr > 0) {
                effect = RenderEffect.createBlurEffect(cr, cr, Shader.TileMode.CLAMP);
            }
            for (View v : targets) {
                v.setRenderEffect(effect);
                v.invalidate();
            }
            mCurContent = target;
            mContentApplied = cr > 0;
            mContentAt = SystemClock.uptimeMillis();
            d("content blur = " + cr + " on " + targets.length + " view(s)");
        } catch (Throwable t) {
            mCurContent = target;
            mContentApplied = false;
            mContentAt = SystemClock.uptimeMillis();
            Log.w(TAG, "applyContentBlur failed", t);
        }
    }

    /** 只糊桌面内容层（Workspace + Hotseat），不碰 Recents/AllApps 所在的 View */
    private View[] contentTargets() {
        Activity a = mActRef.get();
        View wsView = mWorkspaceRef.get();
        View hsView = mHotseatRef.get();
        boolean changed = false;
        if (wsView != null && !wsView.isAttachedToWindow()) {
            d("content view detached -> re-resolve");
            mWorkspaceRef = new WeakReference<>(null);
            mHotseatRef = new WeakReference<>(null);
            wsView = null;
            hsView = null;
            changed = true;
        }
        if (hsView != null && !hsView.isAttachedToWindow()) {
            mHotseatRef = new WeakReference<>(null);
            hsView = null;
        }
        if (wsView == null && a != null) {
            try {
                Object ws = XposedHelpers.callMethod(a, "getWorkspace");
                if (ws instanceof View && ((View) ws).isAttachedToWindow()) {
                    wsView = (View) ws;
                    mWorkspaceRef = new WeakReference<>(wsView);
                    changed = true;
                    d("workspace re-resolved = " + ws.getClass().getName());
                }
            } catch (Throwable ignore) {
            }
            try {
                Object hs = XposedHelpers.callMethod(a, "getHotseat");
                if (hs instanceof View && ((View) hs).isAttachedToWindow()) {
                    hsView = (View) hs;
                    mHotseatRef = new WeakReference<>(hsView);
                }
            } catch (Throwable ignore) {
            }
        }
        if (changed) {
            mCurContent = -999;
            mContentApplied = false;
        }
        if (wsView != null) {
            if (hsView != null) {
                return new View[]{wsView, hsView};
            }
            return new View[]{wsView};
        }
        View decor = mDecorRef.get();
        if (decor != null) {
            return new View[]{decor};
        }
        return NO_VIEWS;
    }

    private void reassertContent(int r) {
        mCurContent = -999;
        mContentApplied = false;
        applyContentBlur(r);
    }

    // ==================================================================
    // Launcher 状态与 DepthController 接管
    // ==================================================================

    public void installStateHook(ClassLoader cl) {
        if (mStateHookInstalled) {
            return;
        }
        mStateHookInstalled = true;
        try {
            Class<?> c = null;
            String[] candidates = {
                    "com.android.launcher3.statemanager.StateManager",
                    "com.android.launcher3.statemanager.LauncherStateManager"
            };
            for (String cn : candidates) {
                try {
                    c = XposedHelpers.findClass(cn, cl);
                    Log.i(TAG, "state manager class = " + cn);
                    break;
                } catch (Throwable ignore) {
                }
            }
            if (c == null) {
                throw new IllegalStateException("StateManager not found");
            }
            XC_MethodHook stateHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object st = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                    if (st == null) {
                        return;
                    }
                    String name = String.valueOf(st);
                    if (name.equals(mLastStateName)) {
                        return;
                    }
                    mLastStateName = name;
                    try {
                        onStateChanged(st);
                    } catch (Throwable t) {
                        Log.w(TAG, "onStateChanged", t);
                    }
                }
            };
            int totalHooks = 0;
            for (String m : new String[]{"goToState", "onStateTransitionStart", "onStateTransitionEnd", "setState"}) {
                try {
                    Set<XC_MethodHook.Unhook> res = XposedBridge.hookAllMethods(c, m, stateHook);
                    if (res != null) {
                        totalHooks += res.size();
                    }
                } catch (Throwable ignore) {
                }
            }
            if (totalHooks > 0) {
                Log.i(TAG, "state hook installed (" + totalHooks + " methods)");
            } else {
                Log.w(TAG, "state hook installed 0 methods on " + c.getName());
            }
        } catch (Throwable t) {
            Log.w(TAG, "state hook unavailable", t);
        }
    }

    public void installDepthHook(ClassLoader cl) {
        if (mDepthHookInstalled) {
            return;
        }
        mDepthHookInstalled = true;
        try {
            XC_MethodHook depthHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mInDepthReassert || mStopped || mPrefs.enabled == 0) {
                        return;
                    }
                    int want = -1;
                    if (mForced) {
                        want = mPrefs.radius;
                    } else if (mInOverviewBlur && mPrefs.overview != 0) {
                        want = mDesiredBgRadius;
                    } else if (mArmed && mCur > 0) {
                        want = mCur;
                    }
                    if (want >= 0) {
                        mInDepthReassert = true;
                        try {
                            trySetRadius(want);
                        } finally {
                            mInDepthReassert = false;
                        }
                    }
                }
            };
            int count = 0;
            for (String cn : new String[]{
                    "com.android.launcher3.statehandlers.DepthController",
                    "com.android.quickstep.util.BaseDepthController"
            }) {
                try {
                    Class<?> dc = XposedHelpers.findClass(cn, cl);
                    Set<XC_MethodHook.Unhook> res = XposedBridge.hookAllMethods(dc, "applyDepthAndBlur", depthHook);
                    if (res != null && !res.isEmpty()) {
                        count += res.size();
                        Log.i(TAG, "depth hook installed on " + cn + " (" + res.size() + ")");
                        break;
                    }
                } catch (Throwable ignore) {
                }
            }
            if (count == 0) {
                Log.w(TAG, "depth hook installed 0 methods");
            }
        } catch (Throwable t) {
            Log.w(TAG, "depth hook unavailable", t);
        }
    }

    private void ensureStateRefs(Activity a) {
        if (mStateRefsReady || a == null) {
            return;
        }
        try {
            Class<?> ls = XposedHelpers.findClass("com.android.launcher3.LauncherState",
                    a.getClass().getClassLoader());
            mNormalState = XposedHelpers.getStaticObjectField(ls, "NORMAL");
            mOverviewState = XposedHelpers.getStaticObjectField(ls, "OVERVIEW");
            mStateRefsReady = true;
            Log.i(TAG, "state refs: NORMAL=" + mNormalState + " OVERVIEW=" + mOverviewState);
        } catch (Throwable t) {
            Log.w(TAG, "state refs unavailable", t);
        }
    }

    private void ensurePolling() {
        if (mStopped || mPolling) {
            return;
        }
        mPolling = true;
        mMain.removeCallbacks(mStatePoll);
        mMain.postDelayed(mStatePoll, POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        if (!mPolling) {
            return;
        }
        mPolling = false;
        mMain.removeCallbacks(mStatePoll);
    }

    /** 仅在前台运行：500ms 轮询兜底状态与看门狗 */
    private final Runnable mStatePoll = new Runnable() {
        @Override
        public void run() {
            if (!mPolling || mStopped) {
                mPolling = false;
                return;
            }
            try {
                pollState();
            } catch (Throwable t) {
                Log.w(TAG, "pollState", t);
            }
            if (mPolling && !mStopped) {
                mMain.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    private void pollState() {
        Activity a = mActRef.get();
        if (a == null) {
            return;
        }
        ensureStateRefs(a);
        try {
            Object sm = XposedHelpers.callMethod(a, "getStateManager");
            if (sm == null) {
                return;
            }
            Object st = XposedHelpers.callMethod(sm, "getState");
            if (st == null) {
                return;
            }
            String name = String.valueOf(st);
            watchdog(name);
            if (name == null || name.equals(mLastStateName)) {
                return;
            }
            mLastStateName = name;
            d("poll state = " + name);
            onStateChanged(st);
        } catch (Throwable t) {
            if (mPollErrLogged) {
                return;
            }
            mPollErrLogged = true;
            Log.w(TAG, "pollState failed", t);
        }
    }

    private void watchdog(String stateName) {
        if (mForced) {
            return;
        }
        boolean focused;
        try {
            Activity a = mActRef.get();
            focused = a != null && a.hasWindowFocus();
        } catch (Throwable t) {
            focused = false;
        }
        if (!"Normal".equals(stateName) || !focused) {
            mLingerFrom = 0L;
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (mArmed) {
            if (mLingerFrom == 0L) {
                mLingerFrom = now;
            }
            long limit = Math.max(2500L, (long) mPrefs.hold + mPrefs.fadeOut + 900L);
            if (now - mLingerFrom < limit) {
                return;
            }
            Log.w(TAG, "watchdog: blurred on desktop for " + (now - mLingerFrom) + "ms -> clear");
            mArmed = false;
            mInOverviewBlur = false;
            mDesiredBgRadius = 0;
            mLingerFrom = 0L;
            clearContentNow();
            mCur = -1;
            setRadius(0);
            return;
        }
        if (now < mFadeDeadline) {
            return;
        }
        boolean staleContent = (mCurContent != 0 || mContentApplied) && now - mContentAt > 600L;
        boolean staleBg = (mCur > 0 && !mInOverviewBlur && mBgAnim == null) && now - mBgAt > 600L;
        if (staleContent || staleBg) {
            Log.w(TAG, "watchdog: stale blur content=" + mCurContent + " bg=" + mCur + " -> clear");
            mDesiredBgRadius = 0;
            clearContentNow();
            if (mCur != 0) {
                mCur = -1;
                setRadius(0);
            }
        }
    }

    private boolean isStateNamed(Object st, String name) {
        if (st == null) {
            return false;
        }
        Object ref = "NORMAL".equals(name) ? mNormalState : mOverviewState;
        if (ref != null && (ref == st || ref.equals(st))) {
            return true;
        }
        String s = String.valueOf(st);
        return s != null && s.contains(name);
    }

    private boolean isDesktopNormalNow() {
        Activity a = mActRef.get();
        if (a == null) {
            return true;
        }
        ensureStateRefs(a);
        if (mNormalState == null) {
            return true;
        }
        try {
            Object sm = XposedHelpers.callMethod(a, "getStateManager");
            Object st = XposedHelpers.callMethod(sm, "getState");
            return isStateNamed(st, "NORMAL");
        } catch (Throwable t) {
            return true;
        }
    }

    /** 状态变化：桌面清残留，Overview 接管壁纸模糊 */
    private void onStateChanged(Object state) {
        Activity a = mActRef.get();
        if (a == null) {
            return;
        }
        ensureStateRefs(a);
        boolean normal = isStateNamed(state, "NORMAL");
        boolean overview = isStateNamed(state, "OVERVIEW");
        Log.i(TAG, "state -> " + state + (normal ? " [NORMAL]" : "") + (overview ? " [OVERVIEW]" : ""));
        Prefs p = mPrefs;
        if (normal) {
            mInOverviewBlur = false;
            mMain.removeCallbacks(mOverviewApplyRun);
            mMain.removeCallbacks(mOverviewReassertRun);
            if (mArmed && p.enabled != 0) {
                mTries = 0;
                mApplyEpoch = mEpoch;
                mMain.removeCallbacks(mApplyRun);
                mMain.post(mApplyRun);
                return;
            }
            mArmed = false;
            mDesiredBgRadius = 0;
            cancelBgAnim();
            mGen++;
            mContentGen++;
            clearContentNow();
            setRadius(0);
        } else if (overview) {
            if (p.enabled == 0 || p.overview == 0) {
                return;
            }
            cancelPendingTasks();
            cancelBgAnim();
            mGen++;
            mContentGen++;
            mArmed = false;
            mInOverviewBlur = true;
            clearContentNow();
            int target = (p.overviewRadius > 0) ? p.overviewRadius : p.radius;
            mOverviewTarget = target;
            mDesiredBgRadius = target;
            mMain.postDelayed(mOverviewApplyRun, 30L);
            mMain.postDelayed(mOverviewReassertRun, 350L);
        } else {
            mInOverviewBlur = false;
            mMain.removeCallbacks(mOverviewApplyRun);
            mMain.removeCallbacks(mOverviewReassertRun);
        }
    }

    private boolean isInOverviewNow() {
        Activity a = mActRef.get();
        if (a == null) {
            return false;
        }
        ensureStateRefs(a);
        try {
            Object sm = XposedHelpers.callMethod(a, "getStateManager");
            Object st = XposedHelpers.callMethod(sm, "getState");
            if (isStateNamed(st, "OVERVIEW")) {
                return true;
            }
            Object target = XposedHelpers.callMethod(sm, "getTargetState");
            return isStateNamed(target, "OVERVIEW");
        } catch (Throwable t) {
            return false;
        }
    }

    private void reassertRadius(int target) {
        final int keep = mCur;
        mCur = -1;
        if (trySetRadius(target)) {
            Log.i(TAG, "silent re-assert blur = " + target);
        } else {
            mCur = keep;
            Log.w(TAG, "silent re-assert skipped (" + target + ")");
        }
    }

    private boolean ensureApi() {
        if (mSetBlur != null) {
            return true;
        }
        if (mApiFailed) {
            return false;
        }
        try {
            Class<?> c = Class.forName("android.view.SurfaceControl$Transaction");
            mTxCtor = c.getConstructor();
            mSetBlur = c.getMethod("setBackgroundBlurRadius", SurfaceControl.class, int.class);
            mApply = c.getMethod("apply");
            try {
                mClose = c.getMethod("close");
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "blur API resolved: " + mSetBlur);
            return true;
        } catch (Throwable t) {
            mApiFailed = true;
            Log.e(TAG, "blur API NOT available", t);
            XposedBridge.log(t);
            return false;
        }
    }

    // ======================================================================
    // 窗口 SurfaceControl
    // ======================================================================

    private void attach(Activity act) {
        if (act != null) {
            mActRef = new WeakReference<>(act);
        }
        Activity a = mActRef.get();
        if (a == null) {
            return;
        }
        try {
            mDecorRef = new WeakReference<>(a.getWindow().getDecorView());
        } catch (Throwable ignore) {
        }
        if (mWorkspaceRef.get() == null) {
            try {
                Object ws = XposedHelpers.callMethod(a, "getWorkspace");
                if (ws instanceof View) {
                    mWorkspaceRef = new WeakReference<>((View) ws);
                    d("workspace = " + ws.getClass().getName());
                }
            } catch (Throwable t) {
                Log.w(TAG, "getWorkspace unavailable", t);
            }
        }
        if (mHotseatRef.get() == null) {
            try {
                Object hs = XposedHelpers.callMethod(a, "getHotseat");
                if (hs instanceof View) {
                    mHotseatRef = new WeakReference<>((View) hs);
                    d("hotseat = " + hs.getClass().getName());
                }
            } catch (Throwable t) {
                Log.w(TAG, "getHotseat unavailable", t);
            }
        }
        ensurePolling();
        if (mAppCtx == null) {
            try {
                mAppCtx = a.getApplicationContext();
            } catch (Throwable ignore) {
            }
            if (mAppCtx != null) {
                mPrefs = Prefs.load(mAppCtx);
                Log.i(TAG, "prefs: enabled=" + mPrefs.enabled + " radius=" + mPrefs.radius
                        + " fadeIn=" + mPrefs.fadeIn + " fadeOut=" + mPrefs.fadeOut + " hold=" + mPrefs.hold);
                ensureObserver();
            }
        }
        if (mSc != null && isValid(mSc)) {
            return;
        }
        SurfaceControl sc = resolveSurface(a);
        if (sc == null || !isValid(sc)) {
            d("attach: SurfaceControl not ready");
            return;
        }
        mSc = sc;
        if (!mAttachLogged) {
            mAttachLogged = true;
            Log.i(TAG, "attached SurfaceControl = " + sc);
        }
    }

    private static boolean isValid(SurfaceControl sc) {
        try {
            return sc.isValid();
        } catch (Throwable t) {
            return false;
        }
    }

    private SurfaceControl resolveSurface(Activity a) {
        try {
            View decor = a.getWindow().getDecorView();
            Object vri = null;
            try {
                vri = XposedHelpers.callMethod(decor, "getViewRootImpl");
            } catch (Throwable ignore) {
            }
            if (vri == null) {
                try {
                    Object ai = XposedHelpers.getObjectField(decor, "mAttachInfo");
                    if (ai != null) {
                        vri = XposedHelpers.getObjectField(ai, "mViewRootImpl");
                    }
                } catch (Throwable ignore) {
                }
            }
            if (vri == null) {
                return null;
            }
            try {
                if (mVriGetSc == null) {
                    mVriGetSc = vri.getClass().getMethod("getSurfaceControl");
                }
                Object scObj = mVriGetSc.invoke(vri);
                if (scObj instanceof SurfaceControl) {
                    return (SurfaceControl) scObj;
                }
            } catch (Throwable ignore) {
            }
            String[] names = {"mSurfaceControl", "mSurface"};
            for (String n : names) {
                try {
                    Object o = XposedHelpers.getObjectField(vri, n);
                    if (o instanceof SurfaceControl) {
                        return (SurfaceControl) o;
                    }
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "resolveSurface", t);
        }
        return null;
    }

    // ======================================================================
    // 调试 / 配置观察
    // ======================================================================

    private void ensureObserver() {
        if (mObserved || mAppCtx == null) {
            return;
        }
        mObserved = true;
        try {
            ContentObserver force = new ContentObserver(mMain) {
                @Override
                public void onChange(boolean self) {
                    applyForce();
                }
            };
            mAppCtx.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(FORCE_KEY), false, force);
            ContentObserver test = new ContentObserver(mMain) {
                @Override
                public void onChange(boolean self) {
                    applyTest();
                }
            };
            mAppCtx.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(TEST_KEY), false, test);

            ContentObserver cfg = new ContentObserver(mMain) {
                @Override
                public void onChange(boolean self) {
                    mPrefs = Prefs.load(mAppCtx);
                    mCurContent = -1;
                    Log.i(TAG, "cfg reloaded: enabled=" + mPrefs.enabled + " radius=" + mPrefs.radius + " noParallax=" + mPrefs.noParallax);
                    if (mPrefs.noParallax != 0) {
                        mLastAppliedNoParallax = 1;
                        applyFixedWallpaperOffset(null, mActRef.get());
                    } else {
                        mLastAppliedNoParallax = 0;
                        syncWallpaperInterpolator();
                    }
                    if (mPrefs.enabled == 0) {
                        mArmed = false;
                        mInOverviewBlur = false;
                        mForced = false;
                        mDesiredBgRadius = 0;
                        cancelPendingTasks();
                        cancelBgAnim();
                        mGen++;
                        mContentGen++;
                        clearContentNow();
                        mCur = -1;
                        setRadius(0);
                    }
                }
            };
            mAppCtx.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Prefs.KEY), false, cfg);
            ensureWallpaperChangedReceiver();
            Log.i(TAG, "settings observers registered");
        } catch (Throwable t) {
            Log.e(TAG, "register observer", t);
        }
    }

    private void applyTest() {
        try {
            String v = Settings.Secure.getString(mAppCtx.getContentResolver(), TEST_KEY);
            if (!"1".equals(v)) {
                return;
            }
            Log.w(TAG, "test hook: simulate stuck armed state");
            mArmed = true;
            mForced = false;
            mArmedAt = SystemClock.uptimeMillis() - 10000L;
            mCur = -1;
            setRadius(mPrefs.radius);
        } catch (Throwable t) {
            Log.e(TAG, "applyTest", t);
        }
    }

    private void applyForce() {
        try {
            String v = Settings.Secure.getString(mAppCtx.getContentResolver(), FORCE_KEY);
            Log.i(TAG, "force key = " + v);
            if ("1".equals(v)) {
                mArmed = true;
                mForced = true;
                mDesiredBgRadius = mPrefs.radius;
                mArmedAt = SystemClock.uptimeMillis();
                setRadius(mPrefs.radius);
            } else {
                mArmed = false;
                mForced = false;
                mInOverviewBlur = false;
                mDesiredBgRadius = 0;
                cancelBgAnim();
                mGen++;
                mContentGen++;
                clearContentNow();
                setRadius(0);
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyForce", t);
        }
    }

    private boolean mWallpaperHooksInstalled = false;
    private boolean mWallpaperRxRegistered = false;
    private WeakReference<Object> mWoiRef = new WeakReference<>(null);
    private volatile boolean mRtlOffset = false;
    private volatile int mLastAppliedNoParallax = -1;
    private volatile boolean mBypassOffsetHook = false;

    private boolean resolveIsRtl(Object woi) {
        if (woi != null) {
            try {
                boolean r = XposedHelpers.getBooleanField(woi, "mIsRtl");
                mRtlOffset = r;
                return r;
            } catch (Throwable ignore) {
            }
        }
        Context c = mAppCtx;
        Activity a = mActRef.get();
        if (c == null && a != null) {
            c = a;
        }
        if (c != null) {
            try {
                boolean r = c.getResources().getConfiguration().getLayoutDirection()
                        == View.LAYOUT_DIRECTION_RTL;
                mRtlOffset = r;
                return r;
            } catch (Throwable ignore) {
            }
        }
        return mRtlOffset;
    }

    private float fixedOffsetX(Object woi) {
        return resolveIsRtl(woi) ? 1.0f : 0.0f;
    }

    public void installWallpaperOffsetHook(ClassLoader cl) {
        if (mWallpaperHooksInstalled || cl == null) {
            return;
        }
        mWallpaperHooksInstalled = true;
        try {
            Class<?> woiCls = XposedHelpers.findClass(
                    "com.android.launcher3.util.WallpaperOffsetInterpolator", cl);
            XposedBridge.hookAllConstructors(woiCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mWoiRef = new WeakReference<>(param.thisObject);
                    resolveIsRtl(param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(woiCls, "setWindowToken", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mWoiRef = new WeakReference<>(param.thisObject);
                    resolveIsRtl(param.thisObject);
                    android.os.IBinder token = (param.args.length > 0 && param.args[0] instanceof android.os.IBinder)
                            ? (android.os.IBinder) param.args[0] : null;
                    if (token != null && mPrefs.noParallax != 0) {
                        applyFixedWallpaperOffset(token, null);
                        mMain.postDelayed(() -> applyFixedWallpaperOffset(null, mActRef.get()), 120L);
                    }
                }
            });
            XposedBridge.hookAllMethods(woiCls, "wallpaperOffsetForScroll", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    mWoiRef = new WeakReference<>(param.thisObject);
                    if (mPrefs.noParallax == 0) {
                        return;
                    }
                    boolean rtl = resolveIsRtl(param.thisObject);
                    if (param.args != null && param.args.length == 3 && param.args[2] instanceof int[]) {
                        int[] offsets = (int[]) param.args[2];
                        if (offsets.length >= 2) {
                            offsets[0] = rtl ? 1 : 0;
                            offsets[1] = 1;
                        }
                        param.setResult(null);
                    } else if (param.args != null && param.args.length == 1) {
                        param.setResult(rtl ? 1.0f : 0.0f);
                    }
                }
            });
            Log.i(TAG, "WallpaperOffsetInterpolator hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "installWallpaperOffsetHook WPOI failed", t);
        }

        try {
            Class<?> ohCls = XposedHelpers.findClass(
                    "com.android.launcher3.util.WallpaperOffsetInterpolator$OffsetHandler", cl);
            XposedBridge.hookAllMethods(ohCls, "handleMessage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mPrefs.noParallax == 0) {
                        return;
                    }
                    forceFixedOffsetHandler(param.thisObject);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mPrefs.noParallax == 0) {
                        return;
                    }
                    forceFixedOffsetHandler(param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(ohCls, "setOffsetSafely", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mPrefs.noParallax == 0) {
                        return;
                    }
                    forceFixedOffsetHandler(param.thisObject);
                }
            });
            mFieldCurOffset = XposedHelpers.findField(ohCls, "mCurrentOffset");
            mFieldFinalOffset = XposedHelpers.findField(ohCls, "mFinalOffset");
            mFieldAnimating = XposedHelpers.findField(ohCls, "mAnimating");
            Log.i(TAG, "WallpaperOffsetInterpolator$OffsetHandler hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "installWallpaperOffsetHook OffsetHandler failed", t);
        }

        try {
            java.lang.reflect.Method setOffsets = XposedHelpers.findMethodExact(
                    android.app.WallpaperManager.class,
                    "setWallpaperOffsets",
                    android.os.IBinder.class, float.class, float.class);
            XposedBridge.hookMethod(setOffsets, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mBypassOffsetHook || mPrefs.noParallax == 0) {
                        return;
                    }
                    if (param.thisObject instanceof android.app.WallpaperManager) {
                        ((android.app.WallpaperManager) param.thisObject).setWallpaperOffsetSteps(0.0f, 1.0f);
                    }
                    param.args[1] = mRtlOffset ? 1.0f : 0.0f;
                    param.args[2] = 0.5f;
                }
            });
            java.lang.reflect.Method setSteps = XposedHelpers.findMethodExact(
                    android.app.WallpaperManager.class,
                    "setWallpaperOffsetSteps",
                    float.class, float.class);
            XposedBridge.hookMethod(setSteps, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mPrefs.noParallax == 0) {
                        return;
                    }
                    param.args[0] = 0.0f;
                }
            });
            Log.i(TAG, "WallpaperManager offset hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "installWallpaperOffsetHook WallpaperManager failed", t);
        }
    }

    private java.lang.reflect.Field mFieldCurOffset;
    private java.lang.reflect.Field mFieldFinalOffset;
    private java.lang.reflect.Field mFieldAnimating;

    private void forceFixedOffsetHandler(Object oh) {
        if (oh == null) {
            return;
        }
        float fixedX = mRtlOffset ? 1.0f : 0.0f;
        try {
            Object ohWm = XposedHelpers.getObjectField(oh, "mWM");
            if (ohWm instanceof android.app.WallpaperManager) {
                ((android.app.WallpaperManager) ohWm).setWallpaperOffsetSteps(0.0f, 1.0f);
            }
            if (mFieldCurOffset != null && mFieldFinalOffset != null && mFieldAnimating != null) {
                mFieldCurOffset.setFloat(oh, fixedX);
                mFieldFinalOffset.setFloat(oh, fixedX);
                mFieldAnimating.setBoolean(oh, false);
            } else {
                XposedHelpers.setObjectField(oh, "mCurrentOffset", fixedX);
                XposedHelpers.setObjectField(oh, "mFinalOffset", fixedX);
                XposedHelpers.setObjectField(oh, "mAnimating", Boolean.FALSE);
            }
        } catch (Throwable ignore) {
        }
    }

    private void syncWallpaperInterpolator() {
        Object woi = mWoiRef.get();
        if (woi != null) {
            try {
                java.lang.reflect.Field f = XposedHelpers.findField(woi.getClass(), "mNumScreens");
                if (f != null) {
                    f.setInt(woi, -1);
                }
            } catch (Throwable ignore) {
            }
            try {
                XposedHelpers.callMethod(woi, "syncWithScroll");
            } catch (Throwable ignore) {
            }
            try {
                XposedHelpers.callMethod(woi, "jumpToFinal");
            } catch (Throwable ignore) {
            }
        }
    }

    private void ensureWallpaperChangedReceiver() {
        if (mWallpaperRxRegistered || mAppCtx == null) {
            return;
        }
        mWallpaperRxRegistered = true;
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(
                    android.content.Intent.ACTION_WALLPAPER_CHANGED);
            android.content.BroadcastReceiver rx = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    if (mPrefs.noParallax == 0) {
                        return;
                    }
                    applyFixedWallpaperOffset(null, mActRef.get());
                    mMain.postDelayed(() -> applyFixedWallpaperOffset(null, mActRef.get()), 200L);
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                mAppCtx.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED);
            } else {
                mAppCtx.registerReceiver(rx, filter);
            }
        } catch (Throwable t) {
            Log.w(TAG, "register wallpaper rx failed", t);
        }
    }

    private void applyFixedWallpaperOffset(android.os.IBinder token, Activity act) {
        if (mPrefs.noParallax == 0) {
            return;
        }
        try {
            Object woi = mWoiRef.get();
            float fixedX = fixedOffsetX(woi);
            if (woi != null) {
                try {
                    Object oh = XposedHelpers.getObjectField(woi, "mHandler");
                    forceFixedOffsetHandler(oh);
                } catch (Throwable ignore) {
                }
            }
            Activity liveAct = act != null ? act : mActRef.get();
            android.os.IBinder tok = (token != null && token.isBinderAlive()) ? token : null;
            if (tok == null && liveAct != null && !liveAct.isDestroyed() && liveAct.getWindow() != null) {
                View decor = liveAct.getWindow().getDecorView();
                if (decor != null && decor.isAttachedToWindow()) {
                    android.os.IBinder dt = decor.getWindowToken();
                    if (dt != null && dt.isBinderAlive()) {
                        tok = dt;
                    }
                }
            }
            if (tok == null) {
                View ws = mWorkspaceRef.get();
                if (ws != null && ws.isAttachedToWindow()) {
                    android.os.IBinder wt = ws.getWindowToken();
                    if (wt != null && wt.isBinderAlive()) {
                        tok = wt;
                    }
                }
            }
            if (tok == null && woi != null) {
                try {
                    Object wt = XposedHelpers.getObjectField(woi, "mWindowToken");
                    if (wt instanceof android.os.IBinder && ((android.os.IBinder) wt).isBinderAlive()) {
                        tok = (android.os.IBinder) wt;
                    }
                } catch (Throwable ignore) {
                }
            }
            if (tok == null || !tok.isBinderAlive()) {
                return;
            }
            Context ctx = mAppCtx;
            if (ctx == null && liveAct != null) {
                ctx = liveAct.getApplicationContext();
            }
            if (ctx == null) {
                return;
            }
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
            if (wm != null) {
                wm.setWallpaperOffsetSteps(0.0f, 1.0f);
                mBypassOffsetHook = true;
                try {
                    wm.setWallpaperOffsets(tok, fixedX, 0.5001f);
                } finally {
                    mBypassOffsetHook = false;
                }
                wm.setWallpaperOffsets(tok, fixedX, 0.5f);
            }
        } catch (Throwable t) {
            d("applyFixedWallpaperOffset err: " + t);
        }
    }

    private static void d(String s) {
        if (LauncherBlurModule.DBG) {
            Log.d(TAG, s);
        }
    }
}
