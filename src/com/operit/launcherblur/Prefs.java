package com.operit.launcherblur;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置：Settings.Secure 里的单键 JSON 风格字符串
 *   launcherblur_cfg = {"enabled":1,"radius":110,"fadeIn":140,"fadeOut":300,"hold":140}
 *
 * 用正则做容错解析，避免引入任何 JSON 库。
 */
public final class Prefs {

    static final String KEY = "launcherblur_cfg";
    private static final Pattern NUM = Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*(-?\\d+)");

    int enabled = 1;
    int radius = 70;
    int fadeIn = 140;
    int fadeOut = 300;
    int hold = 140;
    int content = 1;
    int contentRadius = -1;
    int overview = 1;
    int overviewRadius = -1;
    int resumeBg = 0;
    int noParallax = 1;

    Prefs() {}

    static Prefs load(Context ctx) {
        Prefs p = new Prefs();
        if (ctx == null) {
            return p;
        }
        try {
            String s = Settings.Secure.getString(ctx.getContentResolver(), KEY);
            if (s == null || s.isEmpty()) {
                return p;
            }
            Matcher m = NUM.matcher(s);
            while (m.find()) {
                String k = m.group(1);
                int v;
                try {
                    v = Integer.parseInt(m.group(2));
                } catch (Throwable t) {
                    continue;
                }
                if ("enabled".equals(k)) {
                    p.enabled = v;
                } else if ("radius".equals(k)) {
                    p.radius = v;
                } else if ("fadeIn".equals(k)) {
                    p.fadeIn = v;
                } else if ("fadeOut".equals(k)) {
                    p.fadeOut = v;
                } else if ("hold".equals(k)) {
                    p.hold = v;
                } else if ("content".equals(k)) {
                    p.content = v;
                } else if ("contentRadius".equals(k)) {
                    p.contentRadius = v;
                } else if ("overview".equals(k)) {
                    p.overview = v;
                } else if ("overviewRadius".equals(k)) {
                    p.overviewRadius = v;
                } else if ("resumeBg".equals(k)) {
                    p.resumeBg = v;
                } else if ("noParallax".equals(k)) {
                    p.noParallax = v;
                }
            }
        } catch (Throwable t) {
            Log.w(LauncherBlurModule.TAG, "Prefs.load failed, using defaults", t);
        }
        if (p.radius < 0) p.radius = 0;
        if (p.radius > 300) p.radius = 300;
        if (p.fadeIn < 0) p.fadeIn = 0;
        if (p.fadeIn > 3000) p.fadeIn = 3000;
        if (p.fadeOut < 0) p.fadeOut = 0;
        if (p.fadeOut > 3000) p.fadeOut = 3000;
        if (p.hold < 0) p.hold = 0;
        if (p.hold > 3000) p.hold = 3000;
        if (p.contentRadius < -1) p.contentRadius = -1;
        if (p.contentRadius > 300) p.contentRadius = 300;
        if (p.overviewRadius < -1) p.overviewRadius = -1;
        if (p.overviewRadius > 300) p.overviewRadius = 300;
        return p;
    }
}
