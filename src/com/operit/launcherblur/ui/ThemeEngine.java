package com.operit.launcherblur.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

public final class ThemeEngine {

    private static final int FALLBACK_SEED = 0xFFD0BCFF;
    private static final String LAUNCHER_ALIAS = "com.operit.launcherblur.LauncherActivity";

    public final boolean isDark;
    public final int bgColor;
    public final int cardColor;
    public final int cardElevatedColor;
    public final int cardStrokeColor;

    public final int primary;
    public final int onPrimary;
    public final int primaryContainer;
    public final int onPrimaryContainer;

    public final int heroCardBg;
    public final int heroWatermarkColor;
    public final int heroTextPrimary;
    public final int heroTextSecondary;

    public final int iconBadgeBg;
    public final int iconBadgeFg;

    public final int textPrimary;
    public final int textSecondary;
    public final int textTertiary;
    public final int sectionTitleColor;
    public final int dividerColor;

    public final int switchTrackOn;
    public final int switchThumbOn;
    public final int switchTrackOff;
    public final int switchThumbOff;
    public final int switchStrokeOff;

    public final int warningBg;
    public final int warningStroke;
    public final int warningText;

    public ThemeEngine(Context ctx) {
        int uiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);

        int seed = resolveSeedColor(ctx);
        float[] hsv = new float[3];
        Color.colorToHSV(seed, hsv);
        float hue = hsv[0];
        float sat = hsv[1];

        if (isDark) {
            float pSat = clamp(sat * 0.48f, 0f, 0.85f);
            float cSat = clamp(sat * 0.42f, 0f, 0.75f);
            float nSat = clamp(sat * 0.16f, 0f, 0.24f);

            primary = getSysColor(ctx, android.R.color.system_accent1_200, hsvColor(hue, pSat, 0.96f));
            onPrimary = getSysColor(ctx, android.R.color.system_accent1_800, hsvColor(hue, clamp(pSat * 1.3f, 0f, 0.9f), 0.26f));
            primaryContainer = getSysColor(ctx, android.R.color.system_accent1_700, hsvColor(hue, cSat, 0.36f));
            onPrimaryContainer = getSysColor(ctx, android.R.color.system_accent1_100, hsvColor(hue, pSat * 0.6f, 0.97f));

            bgColor = hsvColor(hue, nSat * 0.85f, 0.065f);
            cardColor = hsvColor(hue, nSat, 0.115f);
            cardElevatedColor = hsvColor(hue, nSat * 1.15f, 0.165f);
            cardStrokeColor = withAlpha(primary, 28);

            heroCardBg = hsvColor(hue, clamp(cSat * 0.72f, 0f, 0.50f), 0.27f);
            heroWatermarkColor = withAlpha(primary, 150);
            heroTextPrimary = 0xFFF5F3F9;
            heroTextSecondary = withAlpha(0xFFEAE6F2, 204);

            iconBadgeBg = withAlpha(primaryContainer, 210);
            iconBadgeFg = onPrimaryContainer;

            textPrimary = 0xFFF3EFF7;
            textSecondary = 0xFFA29DAB;
            textTertiary = 0xFF736E7C;
            sectionTitleColor = primary;
            dividerColor = 0x1AFFFFFF;

            switchTrackOn = primary;
            switchThumbOn = hsvColor(hue, clamp(pSat * 1.2f, 0f, 0.8f), 0.26f);
            switchTrackOff = hsvColor(hue, nSat, 0.22f);
            switchThumbOff = 0xFF96919E;
            switchStrokeOff = 0x33FFFFFF;

            warningBg = 0xFF3B241E;
            warningStroke = 0x55FFB4AB;
            warningText = 0xFFFFDAD6;
        } else {
            float pSat = clamp(sat * 0.78f, 0f, 0.92f);
            float cSat = clamp(sat * 0.24f, 0f, 0.45f);
            float nSat = clamp(sat * 0.07f, 0f, 0.12f);

            primary = getSysColor(ctx, android.R.color.system_accent1_600, hsvColor(hue, pSat, 0.56f));
            onPrimary = 0xFFFFFFFF;
            primaryContainer = getSysColor(ctx, android.R.color.system_accent1_100, hsvColor(hue, cSat, 0.94f));
            onPrimaryContainer = getSysColor(ctx, android.R.color.system_accent1_900, hsvColor(hue, pSat, 0.22f));

            bgColor = hsvColor(hue, nSat * 0.7f, 0.965f);
            cardColor = 0xFFFFFFFF;
            cardElevatedColor = hsvColor(hue, nSat * 1.2f, 0.93f);
            cardStrokeColor = withAlpha(primary, 28);

            heroCardBg = primaryContainer;
            heroWatermarkColor = withAlpha(primary, 110);
            heroTextPrimary = onPrimaryContainer;
            heroTextSecondary = withAlpha(onPrimaryContainer, 195);

            iconBadgeBg = primaryContainer;
            iconBadgeFg = onPrimaryContainer;

            textPrimary = 0xFF1C1B1F;
            textSecondary = 0xFF5E5A66;
            textTertiary = 0xFF8A8593;
            sectionTitleColor = primary;
            dividerColor = 0x16000000;

            switchTrackOn = primary;
            switchThumbOn = 0xFFFFFFFF;
            switchTrackOff = hsvColor(hue, nSat * 1.5f, 0.86f);
            switchThumbOff = 0xFF74707D;
            switchStrokeOff = 0x22000000;

            warningBg = 0xFFFFECEA;
            warningStroke = 0x44BA1A1A;
            warningText = 0xFF690005;
        }
    }

    private static int resolveSeedColor(Context ctx) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return ctx.getColor(android.R.color.system_accent1_200);
            } catch (Throwable ignored) {
            }
        }
        return FALLBACK_SEED;
    }

    private static int getSysColor(Context ctx, int resId, int fallback) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return ctx.getColor(resId);
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    public static int hsvColor(float h, float s, float v) {
        return Color.HSVToColor(new float[]{h, clamp(s, 0f, 1f), clamp(v, 0f, 1f)});
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (clampInt(alpha, 0, 255) << 24);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static boolean isLauncherIconHidden(Context ctx) {
        try {
            ComponentName cn = new ComponentName(ctx, LAUNCHER_ALIAS);
            int state = ctx.getPackageManager().getComponentEnabledSetting(cn);
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setLauncherIconHidden(Context ctx, boolean hide) {
        try {
            ComponentName cn = new ComponentName(ctx, LAUNCHER_ALIAS);
            ctx.getPackageManager().setComponentEnabledSetting(
                    cn,
                    hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );
        } catch (Throwable ignored) {
        }
    }
}
