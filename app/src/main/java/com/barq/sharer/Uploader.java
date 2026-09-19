package com.barq.sharer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** قراءة الملفات اللي وصلت من المشاركة ورفعها على السيرفر */
public class Uploader {

    /** ملف واحد اتقرا من المشاركة */
    public static class Item {
        public final String name;
        public final byte[] bytes;
        Item(String name, byte[] bytes) { this.name = name; this.bytes = bytes; }
    }

    /** نتيجة الرفع */
    public static class Result {
        public final boolean ok;
        public final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    /** بيطلع اسم الملف الحقيقي من الـ Uri اللي التطبيق المشارِك بعته */
    public static String resolveName(Context ctx, Uri uri) {
        String name = null;
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) {
            String path = uri.getLastPathSegment();
            name = (path == null || path.trim().isEmpty()) ? "ملف.xlsx" : path;
        }
        return name;
    }

    /** بيقرا محتوى الملف الفعلي (البايتات) من الـ Uri */
    public static byte[] readBytes(Context ctx, Uri uri) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("تعذر فتح الملف");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** بيقرا كل الملفات اللي جت في المشاركة */
    public static List<Item> readAll(Context ctx, List<Uri> uris) {
        List<Item> items = new ArrayList<>();
        for (Uri u : uris) {
            try {
                byte[] b = readBytes(ctx, u);
                if (b.length > 0) items.add(new Item(resolveName(ctx, u), b));
            } catch (Exception ignored) { }
        }
        return items;
    }

    /**
     * بيرفع الملفات على السيرفر على نفس المسار اللي البرنامج بيستخدمه أصلًا.
     * بنبعت كوكيز تسجيل الدخول اللي محفوظة من الشاشة الرئيسية (WebView) عشان
     * السيرفر يعرف إن ده نفس المستخدم اللي مسجّل دخول.
     */
    public static Result upload(Context ctx, String dest, List<Item> items) {
        String base = Prefs.getUrl(ctx);
        if (base == null || base.trim().isEmpty()) {
            return new Result(false, "لازم تحط رابط البرنامج الأول من الإعدادات");
        }
        if (items.isEmpty()) {
            return new Result(false, "مفيش ملفات اتقرت من المشاركة");
        }

        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("filename", it.name);
                o.put("content", Base64.encodeToString(it.bytes, Base64.NO_WRAP));
                arr.put(o);
            }
            JSONObject body = new JSONObject();
            body.put("dest", dest);
            body.put("files", arr);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(base + "/share-target/confirm").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);

            String cookie = CookieManager.getInstance().getCookie(base);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String text = "";
            if (is != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
                text = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }

            if (code == 401 || code == 403) {
                return new Result(false, "لازم تسجّل دخول في البرنامج الأول (افتح التطبيق وسجّل دخول)");
            }

            try {
                JSONObject res = new JSONObject(text);
                if (res.optBoolean("ok", false)) {
                    int added = res.optInt("added", items.size());
                    return new Result(true, "تم رفع " + added + " ملف بنجاح");
                }
                String detail = res.optString("detail", "");
                return new Result(false, detail.isEmpty() ? "السيرفر رفض الملف" : detail);
            } catch (Exception parseErr) {
                if (code >= 200 && code < 300) return new Result(true, "تم الرفع");
                return new Result(false, "رد غير متوقع من السيرفر (كود " + code + ")");
            }

        } catch (Exception e) {
            return new Result(false, "تعذر الاتصال بالبرنامج - اتأكد إن السيرفر شغال والرابط صح\n(" + e.getMessage() + ")");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
