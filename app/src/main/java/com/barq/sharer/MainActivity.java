package com.barq.sharer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** الشاشة الرئيسية: بتعرض البرنامج نفسه جوه التطبيق */
public class MainActivity extends Activity {

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQ_FILE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b1e3d"));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient());

        // مهم: من غير ده أزرار رفع الملفات اللي جوه البرنامج مش هتشتغل
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "تعذر فتح ملفات الهاتف", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        // جسر جافا-سكريبت عشان نقدر نلصق من حافظة الهاتف - الـWebView العادي مش بيدي
        // إذن وصول للحافظة عن طريق navigator.clipboard خالص، فلازم جسر ناتيف زي ده
        web.addJavascriptInterface(new ClipboardBridge(), "AndroidClipboard");

        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // زرار صغير للإعدادات (تغيير رابط السيرفر) - في الركن تحت
        Button gear = new Button(this);
        gear.setText("⚙");
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        gear.setAlpha(0.45f);
        gear.setOnClickListener(v -> askForUrl(false));
        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 46, getResources().getDisplayMetrics());
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(size, size);
        glp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        root.addView(gear, glp);

        setContentView(root);

        if (Prefs.hasUrl(this)) {
            web.loadUrl(Prefs.getUrl(this));
        } else {
            askForUrl(true);
        }
    }

    /** نافذة إدخال/تعديل رابط البرنامج */
    private void askForUrl(final boolean firstTime) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18, getResources().getDisplayMetrics());
        box.setPadding(pad, pad, pad, 0);

        TextView hint = new TextView(this);
        hint.setText("اكتب رابط البرنامج زي ما بيظهرلك في نافذة cloudflared، مثال:\nhttps://xxxx-xxxx.trycloudflare.com");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(hint);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setHint("https://...");
        input.setText(Prefs.getUrl(this));
        box.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("رابط البرنامج")
                .setView(box)
                .setCancelable(!firstTime)
                .setPositiveButton("حفظ", (d, w) -> {
                    String url = Prefs.normalize(input.getText().toString());
                    if (url.isEmpty()) {
                        Toast.makeText(this, "لازم تكتب الرابط", Toast.LENGTH_SHORT).show();
                        askForUrl(firstTime);
                        return;
                    }
                    Prefs.setUrl(this, url);
                    web.loadUrl(url);
                    Toast.makeText(this, "اتحفظ", Toast.LENGTH_SHORT).show();
                });
        if (!firstTime) b.setNegativeButton("إلغاء", null);
        b.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /**
     * جسر بيدي صفحة الويب جوه التطبيق وصول مباشر لحافظة الهاتف (نسخ/لصق).
     * ده لازم يكون كود ناتيف: WebView مفيهوش أي إذن أصلاً اسمه "حافظة" في نظام
     * الأذونات بتاعه (onPermissionRequest)، فـ navigator.clipboard.readText() في
     * جافا-سكريبت مستحيل يشتغل جوه تطبيق APK مهما حاولنا - لازم الجسر ده بالظبط.
     */
    private class ClipboardBridge {
        @JavascriptInterface
        public String readClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence text = clip.getItemAt(0).coerceToText(MainActivity.this);
                return text == null ? "" : text.toString();
            } catch (Exception e) {
                return "";
            }
        }
    }
}
