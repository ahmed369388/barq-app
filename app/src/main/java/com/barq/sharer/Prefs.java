package com.barq.sharer;

import android.content.Context;
import android.content.SharedPreferences;

/** تخزين بسيط لرابط السيرفر عشان نقدر نغيّره في أي وقت من غير ما نبني التطبيق من جديد */
public class Prefs {
    private static final String FILE = "barq_prefs";
    private static final String KEY_URL = "server_url";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getUrl(Context c) {
        return sp(c).getString(KEY_URL, "");
    }

    public static void setUrl(Context c, String url) {
        sp(c).edit().putString(KEY_URL, normalize(url)).apply();
    }

    public static boolean hasUrl(Context c) {
        return getUrl(c) != null && !getUrl(c).trim().isEmpty();
    }

    /** بنظبط الرابط: نشيل المسافات، نضيف https لو الشخص نساها، ونشيل أي / في الآخر */
    public static String normalize(String url) {
        if (url == null) return "";
        url = url.trim();
        if (url.isEmpty()) return "";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
