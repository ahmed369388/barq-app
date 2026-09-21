package com.barq.sharer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * بتستقبل الملفات المُشاركة من واتساب (أو أي تطبيق) وبتسأل المستخدم الملف ده يتحط فين،
 * وبترفعه على السيرفر على طول.
 *
 * دي الحاجة اللي مكنتش ممكنة في تطبيق الويب: واتساب مكانش بيسلّم الملف نفسه لتطبيقات الويب،
 * كان بيبعت اسم الملف كنص بس. أما التطبيق الأصلي (APK) ده فبياخد الملف الحقيقي كامل.
 */
public class ShareActivity extends Activity {

    // ألوان الشاشة بتتحدد حسب وضع البرنامج نفسه (ليلي/نهاري) اللي الصفحة بتحفظه في Prefs
    private static final int NAVY_DARK = Color.parseColor("#0b1e3d");   // خلفية الوضع الليلي
    private static final int CARD_DARK = Color.parseColor("#122a52");
    private static final int BG_LIGHT = Color.parseColor("#F4F7FB");    // خلفية الوضع النهاري
    private static final int CARD_LIGHT = Color.parseColor("#FFFFFF");
    private static final int TEXT_LIGHT = Color.parseColor("#0b1e3d");

    // تدرجات أزرار الاختيارات - نفس ألوان صفحة الفرز بالظبط:
    // ملف إحالة = ذهبي، ملف تشيك = أخضر، ملف تسجيل = أزرق (لون ملف الداتا)
    private static final int[] GRAD_REF = { Color.parseColor("#F3E1A0"), Color.parseColor("#D4AF37"), Color.parseColor("#B8942C") };
    private static final int[] GRAD_CHECK = { Color.parseColor("#40916C"), Color.parseColor("#2D6A4F"), Color.parseColor("#1B4332") };
    private static final int[] GRAD_MAIN = { Color.parseColor("#7DD3FC"), Color.parseColor("#2563EB"), Color.parseColor("#0C3B82") };

    // ألوان نافذة كلمة السر - زيتوني متدرج (بطلب المستخدم)
    private static final int[] GRAD_OLIVE_BG = { Color.parseColor("#3E4A2A"), Color.parseColor("#2B331C"), Color.parseColor("#1A2012") };
    private static final int[] GRAD_OLIVE_BTN = { Color.parseColor("#8FA857"), Color.parseColor("#6B7F3C"), Color.parseColor("#4B5A29") };
    private static final int OLIVE_LINE = Color.parseColor("#6B7F3C");
    private static final int OLIVE_TEXT_SOFT = Color.parseColor("#D7E0C4");

    private boolean darkMode = false;

    private final List<Uploader.Item> items = new ArrayList<>();
    private TextView status;
    private final List<Button> destButtons = new ArrayList<>();
    private int totalAdded = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        if (!Prefs.hasUrl(this)) {
            Toast.makeText(this, "افتح تطبيق برق الأول وحط رابط البرنامج", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        List<Uri> uris = extractUris(getIntent());
        if (uris.isEmpty()) {
            status.setText("مفيش ملفات في المشاركة دي");
            return;
        }

        status.setText("جاري قراءة الملف...");
        setButtonsEnabled(false);
        new Thread(() -> {
            final List<Uploader.Item> read = Uploader.readAll(ShareActivity.this, uris);
            runOnUiThread(() -> {
                items.clear();
                items.addAll(read);
                if (items.isEmpty()) {
                    status.setText("تعذر قراءة الملف من التطبيق اللي شاركت منه");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (Uploader.Item it : items) {
                    sb.append("📄 ").append(it.name)
                      .append("  (").append(Math.max(1, it.bytes.length / 1024)).append(" ك.ب)\n");
                }
                status.setText(sb.toString().trim());
                setButtonsEnabled(true);
            });
        }).start();
    }

    /** بيطلع الملفات من نية المشاركة سواء ملف واحد أو أكتر */
    private List<Uri> extractUris(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null) return out;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Uri u : list) if (u != null) out.add(u);
            }
        }
        return out;
    }

    private void send(final String dest) {
        if (items.isEmpty()) return;
        totalAdded = 0;
        setButtonsEnabled(false);
        status.setText("جاري الرفع...");
        uploadItems(dest, items);
    }

    /**
     * بيرفع مجموعة ملفات، ولو السيرفر رد إن أي ملف منها محتاج كلمة سر (ملف إكسل محمي)
     * بيفتح نافذة يطلب فيها كلمة السر بدل ما يرفض الملف على طول - عشان أي حد يستخدم
     * البرنامج يقدر يشارك ملف محمي بكلمة سر من واتساب من غير ما يتوه.
     */
    private void uploadItems(final String dest, final List<Uploader.Item> toSend) {
        new Thread(() -> {
            final Uploader.Result r = Uploader.upload(ShareActivity.this, dest, toSend);
            runOnUiThread(() -> {
                totalAdded += extractAddedCount(r);
                if (!r.needPassword.isEmpty()) {
                    askPasswordAndRetry(dest, toSend, r.needPassword);
                    return;
                }
                String finalMsg = totalAdded > 0
                        ? "تم رفع " + totalAdded + " ملف بنجاح"
                        : r.message;
                status.setText(finalMsg);
                if (totalAdded > 0) {
                    Toast.makeText(ShareActivity.this, finalMsg, Toast.LENGTH_SHORT).show();
                    status.postDelayed(this::finish, 900);
                } else {
                    setButtonsEnabled(true);
                }
            });
        }).start();
    }

    /** بيلاقط عدد الملفات اللي اتضافت فعلًا من رسالة النتيجة (لو موجود رقم فيها) */
    private int extractAddedCount(Uploader.Result r) {
        if (!r.ok) return 0;
        try {
            String digits = r.message.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 1 : Integer.parseInt(digits);
        } catch (Exception e) {
            return 1;
        }
    }

    /** بيفتح نافذة كلمة سر لأول ملف من قايمة "محتاج كلمة سر"، وبعد ما المستخدم يكتبها بيعيد رفع نفس الملف بس */
    private void askPasswordAndRetry(final String dest, final List<Uploader.Item> allSent, final List<String> needPwNames) {
        final String targetName = needPwNames.get(0);
        Uploader.Item target = null;
        for (Uploader.Item it : allSent) {
            if (it.name.equals(targetName)) { target = it; break; }
        }
        if (target == null) {
            status.setText("تعذر تحديد الملف المحتاج كلمة سر");
            setButtonsEnabled(true);
            return;
        }
        final Uploader.Item fTarget = target;

        final boolean isRetry = fTarget.password != null && !fTarget.password.isEmpty();
        showPasswordDialog(targetName, isRetry, new PasswordCallback() {
            @Override
            public void onEntered(String password) {
                fTarget.password = password;
                List<Uploader.Item> retryOnly = new ArrayList<>();
                retryOnly.add(fTarget);
                status.setText("جاري الرفع...");
                uploadItems(dest, retryOnly);
            }

            @Override
            public void onSkipped() {
                List<String> remaining = new ArrayList<>(needPwNames);
                remaining.remove(0);
                if (remaining.isEmpty()) {
                    String finalMsg = totalAdded > 0 ? "تم رفع " + totalAdded + " ملف بنجاح" : "اتلغى";
                    status.setText(finalMsg);
                    if (totalAdded > 0) status.postDelayed(ShareActivity.this::finish, 900);
                    else setButtonsEnabled(true);
                } else {
                    askPasswordAndRetry(dest, allSent, remaining);
                }
            }
        });
    }

    private interface PasswordCallback {
        void onEntered(String password);
        void onSkipped();
    }

    /**
     * نافذة كلمة السر بشكل احترافي مصمم بالكامل (مش نافذة النظام العادية): خلفية زيتونية
     * متدرجة، حواف دايرية، وأزرار متدرجة - بطلب المستخدم.
     */
    private void showPasswordDialog(String fileName, boolean isRetry, final PasswordCallback cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(22), dp(22), dp(18));
        GradientDrawable boxBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, GRAD_OLIVE_BG);
        boxBg.setCornerRadius(dp(20));
        boxBg.setStroke(dp(1), OLIVE_LINE);
        box.setBackground(boxBg);

        TextView lock = new TextView(this);
        lock.setText("🔒");
        lock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        lock.setGravity(Gravity.CENTER);
        box.addView(lock);

        TextView title = new TextView(this);
        title.setText("الملف محمي بكلمة سر");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(6), 0, dp(4));
        box.addView(title);

        TextView nameView = new TextView(this);
        nameView.setText(fileName);
        nameView.setTextColor(OLIVE_TEXT_SOFT);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        nameView.setGravity(Gravity.CENTER);
        nameView.setMaxLines(2);
        nameView.setPadding(0, 0, 0, dp(10));
        box.addView(nameView);

        TextView msg = new TextView(this);
        msg.setText(isRetry ? "كلمة السر غلط - جرّب تاني" : "اكتب كلمة سر الملف عشان نقدر نرفعه");
        msg.setTextColor(isRetry ? Color.parseColor("#FFB4A2") : OLIVE_TEXT_SOFT);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, 0, 0, dp(12));
        box.addView(msg);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("كلمة السر");
        input.setHintTextColor(Color.parseColor("#9AA88A"));
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setGravity(Gravity.CENTER);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#1E2617"));
        inputBg.setCornerRadius(dp(12));
        inputBg.setStroke(dp(1), OLIVE_LINE);
        input.setBackground(inputBg);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.bottomMargin = dp(16);
        box.addView(input, ilp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button ok = new Button(this);
        ok.setText("تم");
        ok.setTextColor(Color.WHITE);
        ok.setAllCaps(false);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable okBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, GRAD_OLIVE_BTN);
        okBg.setCornerRadius(dp(12));
        ok.setBackground(okBg);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp(48), 1.3f);
        okLp.leftMargin = dp(5);
        ok.setLayoutParams(okLp);

        Button skip = new Button(this);
        skip.setText("تجاهل");
        skip.setTextColor(OLIVE_TEXT_SOFT);
        skip.setAllCaps(false);
        skip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable skipBg = new GradientDrawable();
        skipBg.setColor(Color.parseColor("#00000000"));
        skipBg.setCornerRadius(dp(12));
        skipBg.setStroke(dp(1), OLIVE_LINE);
        skip.setBackground(skipBg);
        LinearLayout.LayoutParams skipLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        skipLp.rightMargin = dp(5);
        skip.setLayoutParams(skipLp);

        row.addView(ok);
        row.addView(skip);
        box.addView(row);

        // إطار خارجي شفاف عشان النافذة تبان طايرة وسط الشاشة بمسافات من الجناب
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(12), dp(6), dp(12), dp(6));
        wrapper.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(wrapper)
                .setCancelable(false)
                .create();
        if (dialog.getWindow() != null) {
            // بنشيل خلفية النافذة البيضا بتاعة النظام عشان تصميمنا الزيتوني يبان لوحده
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        ok.setOnClickListener(v -> {
            String pw = input.getText().toString();
            dialog.dismiss();
            cb.onEntered(pw);
        });
        skip.setOnClickListener(v -> {
            dialog.dismiss();
            cb.onSkipped();
        });

        dialog.show();
    }

    private void setButtonsEnabled(boolean on) {
        for (Button b : destButtons) {
            b.setEnabled(on);
            b.setAlpha(on ? 1f : 0.45f);
        }
    }

    // ----------------- بناء الواجهة -----------------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        // نفس وضع البرنامج (ليلي/نهاري) - الصفحة بتحفظه في Prefs كل ما يتغيّر
        darkMode = Prefs.isDarkMode(this);
        int screenBg = darkMode ? NAVY_DARK : BG_LIGHT;
        int cardBg = darkMode ? CARD_DARK : CARD_LIGHT;
        int textColor = darkMode ? Color.WHITE : TEXT_LIGHT;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(screenBg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg);
        bg.setCornerRadius(dp(16));
        if (!darkMode) {
            // في الوضع النهاري بنحط برواز خفيف عشان الكارت الأبيض يبان على الخلفية الفاتحة
            bg.setStroke(dp(1), Color.parseColor("#D8E0EC"));
        }
        card.setBackground(bg);

        // لوجو برق فوق الشاشة
        ImageView logo = new ImageView(this);
        try {
            logo.setImageResource(R.mipmap.barq_logo);
        } catch (Exception ignored) { }
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        logoLp.bottomMargin = dp(10);
        logo.setLayoutParams(logoLp);
        card.addView(logo);

        TextView title = new TextView(this);
        title.setText("الملف ده يتحط فين؟");
        title.setTextColor(textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        card.addView(title);

        status = new TextView(this);
        status.setTextColor(textColor);
        status.setAlpha(0.85f);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(6), dp(6), dp(6), dp(14));
        card.addView(status);

        // الترتيب المطلوب: إحالة (ذهبي) ثم تشيك (أخضر) ثم تسجيل (أزرق) - بتدرج لوني زي صفحة الفرز
        card.addView(makeButton("📎 ملف إحالة", GRAD_REF, NAVY_DARK, "ref"));
        card.addView(makeButton("🛡️ ملف تشيك (يستبدل اللي قبله)", GRAD_CHECK, Color.WHITE, "check"));
        card.addView(makeButton("📄 ملف تسجيل (يضاف على الداتا)", GRAD_MAIN, Color.WHITE, "main"));

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.addView(root);
        setContentView(scroll);
    }

    private Button makeButton(String text, int[] gradientColors, int textColor, final String dest) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        // تدرج لوني من الفاتح للغامق (نفس اتجاه أزرار البرنامج في الويب)
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, gradientColors);
        d.setCornerRadius(dp(12));
        b.setBackground(d);
        b.setOnClickListener(v -> send(dest));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.bottomMargin = dp(10);
        b.setLayoutParams(lp);

        destButtons.add(b);
        b.setEnabled(false);
        b.setAlpha(0.45f);
        return b;
    }
                        }
