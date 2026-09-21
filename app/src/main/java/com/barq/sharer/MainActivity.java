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
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;

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
        // لازم عشان الروابط اللي بتفتح في نافذة جديدة (target="_blank" أو window.open) تشتغل -
        // زي زرار "اتجاهات" وزرار "واتساب" في بطاقات الخريطة (من غير ده كانوا مابيعملوش أي حاجة)
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        // مهم: أي رابط بيفتح تطبيق تاني على الموبايل (خرايط جوجل/واتساب/اتصال/بريد) لازم نفتحه
        // بالتطبيق نفسه مش جوه صفحة الويب - من غير ده زراري "اتجاهات" و"واتساب" في بطاقات الخريطة
        // كانوا بيطلعوا خطأ ERR_UNKNOWN_URL_SCHEME لأن الـWebView مش بيعرف الروابط دي أصلاً
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalUrl(request.getUrl() == null ? null : request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalUrl(url);
            }
        });

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

            /**
             * بيشتغل لما الصفحة تحاول تفتح رابط في نافذة جديدة (target="_blank" أو window.open) -
             * زي زرار "اتجاهات" وزرار "واتساب" في بطاقات الخريطة. بنمسك الرابط ونفتحه في تطبيقه
             * الصح على الموبايل (خرايط/واتساب)، ولو رابط عادي بنفتحه جوه البرنامج نفسه.
             */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        openPopupUrl(request.getUrl() == null ? null : request.getUrl().toString());
                        return true;
                    }

                    @SuppressWarnings("deprecation")
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        openPopupUrl(url);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }
        });

        // جسر جافا-سكريبت عشان نقدر نلصق من حافظة الهاتف - الـWebView العادي مش بيدي
        // إذن وصول للحافظة عن طريق navigator.clipboard خالص، فلازم جسر ناتيف زي ده
        web.addJavascriptInterface(new ClipboardBridge(), "AndroidClipboard");

        // جسر عشان زرار "مشاركة" (صورة نتائج الفرز) يفتح شاشة مشاركة أندرويد الحقيقية
        // (زي مشاركة صورة من المعرض) بدل ما يحفظ الصورة في التنزيلات بس - ده اللي بيخلي
        // اختيار واتساب من شاشة المشاركة يوديك لواتساب فعلًا مع الصورة مرفقة جاهزة
        web.addJavascriptInterface(new ShareBridge(), "AndroidShare");

        // جسر عشان زرار "فتح إكسل" يفتح ملف الإكسل المُصدَّر على طول في إكسل (أو أي برنامج
        // تاني عند المستخدم بيفتح xlsx) بدل ما يتحفظ في التنزيلات بس ويسيب المستخدم يدور عليه
        web.addJavascriptInterface(new OpenFileBridge(), "AndroidOpenFile");

        // جسر بيخلي الصفحة تبلّغ التطبيق بالوضع الليلي/النهاري الحالي عشان شاشة استقبال الملفات
        // المشاركة من واتساب تتلون بنفس وضع البرنامج
        web.addJavascriptInterface(new ThemeBridge(), "AndroidTheme");

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

    /** رابط جاي من "نافذة جديدة": يفتح في تطبيقه الخارجي لو رابط تطبيق، وإلا يفتح جوه البرنامج */
    private void openPopupUrl(String url) {
        if (url == null || url.isEmpty()) return;
        if (!handleExternalUrl(url) && web != null) {
            web.loadUrl(url);
        }
    }

    /**
     * بيمسك الروابط اللي المفروض تفتح تطبيق تاني على الموبايل (مش جوه صفحة البرنامج):
     *   • intent:// و whatsapp:// و geo: و tel: و mailto: - الـWebView مابيعرفهاش خالص
     *     وكان بيطلع ERR_UNKNOWN_URL_SCHEME (ده كان سبب إن زرار "اتجاهات" مش شغال)
     *   • روابط خرايط جوجل و wa.me - دول روابط https عادية بس المفروض تفتح في تطبيقاتها
     *     (خرايط/واتساب) مش جوه البرنامج (ده كان سبب إن زرار "واتساب" مش شغال)
     * بيرجع true يعني "أنا اتصرفت في الرابط ده، متفتحوش جوه الصفحة".
     */
    private boolean handleExternalUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();

        boolean isAppScheme = lower.startsWith("intent:") || lower.startsWith("whatsapp:")
                || lower.startsWith("geo:") || lower.startsWith("tel:") || lower.startsWith("mailto:")
                || lower.startsWith("sms:") || lower.startsWith("market:");
        boolean isExternalWeb = lower.contains("wa.me/") || lower.contains("api.whatsapp.com")
                || lower.contains("google.com/maps") || lower.contains("maps.google.")
                || lower.contains("maps.app.goo.gl") || lower.contains("goo.gl/maps");

        if (!isAppScheme && !isExternalWeb) return false;

        try {
            Intent intent;
            if (lower.startsWith("intent:")) {
                // روابط intent:// بتتفك لنية أندرويد حقيقية، ولو التطبيق المطلوب مش متسطب
                // بنستخدم الرابط البديل (browser_fallback_url) اللي جواها لو موجود
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                    return true;
                } catch (android.content.ActivityNotFoundException notFound) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null && !fallback.isEmpty()) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return true;
                    }
                    Toast.makeText(this, "مفيش تطبيق على الجهاز يقدر يفتح ده", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح الرابط: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return true;
        }
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

    /**
     * جسر بيدي صفحة الويب القدرة تفتح شاشة "مشاركة" أندرويد الحقيقية (زي مشاركة صورة من
     * المعرض بالظبط) - عشان تقدر تختار واتساب من القايمة ويوصله الملف جاهز مرفق على طول،
     * بدل ما البرنامج يضطر يحفظ الملف في مجلد التنزيلات ويسيب المستخدم يرفقه بنفسه يدوي.
     */
    private class ShareBridge {
        @JavascriptInterface
        public void shareFile(final String base64Data, final String filename, final String mime) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    File dir = new File(getCacheDir(), "shared");
                    dir.mkdirs();
                    // بنشيل أي ملفات قديمة في المجلد ده عشان مايتراكمش
                    File[] old = dir.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    File file = new File(dir, filename);
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(bytes);
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", file);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "مشاركة"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذرت المشاركة: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /** جسر بيستقبل الوضع الليلي/النهاري من صفحة البرنامج ويحفظه عشان شاشة المشاركة تستخدمه */
    private class ThemeBridge {
        @JavascriptInterface
        public void setDarkMode(final boolean dark) {
            try {
                Prefs.setDarkMode(MainActivity.this, dark);
            } catch (Exception ignored) { }
        }
    }

    /**
     * جسر بيدي صفحة الويب القدرة تفتح ملف (زي الإكسل المُصدَّر) على طول في برنامج تاني عند
     * المستخدم (إكسل/WPS/أي فيوور xlsx) عن طريق Intent.ACTION_VIEW - بدل ما يتحفظ في مجلد
     * التنزيلات بس ويسيب المستخدم يفتحه يدوي بنفسه.
     */
    private class OpenFileBridge {
        @JavascriptInterface
        public void openFile(final String base64Data, final String filename, final String mime) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    File dir = new File(getCacheDir(), "open");
                    dir.mkdirs();
                    // بنشيل أي ملفات قديمة في المجلد ده عشان مايتراكمش
                    File[] old = dir.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    File file = new File(dir, filename);
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(bytes);
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", file);
                    Intent view = new Intent(Intent.ACTION_VIEW);
                    view.setDataAndType(uri, mime == null || mime.isEmpty()
                            ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : mime);
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(view);
                    } catch (android.content.ActivityNotFoundException notFound) {
                        // مفيش برنامج على الجهاز عارف يفتح إكسل - نديله شاشة اختيار مشاركة/حفظ بدلها
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType(view.getType());
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(share, "فتح باستخدام"));
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر فتح الملف: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
                                   }
