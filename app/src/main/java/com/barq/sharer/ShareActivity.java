package com.barq.sharer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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

    private static final int NAVY = Color.parseColor("#0b1e3d");
    private static final int CARD = Color.parseColor("#122a52");
    private static final int GOLD = Color.parseColor("#c9a35a");

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

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("كلمة السر");

        final boolean isRetry = fTarget.password != null && !fTarget.password.isEmpty();
        new AlertDialog.Builder(this)
                .setTitle("الملف \"" + targetName + "\" محمي بكلمة سر")
                .setMessage(isRetry ? "كلمة السر غلط - جرّب تاني" : "اكتب كلمة سر الملف عشان نقدر نرفعه")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("تم", (d, w) -> {
                    fTarget.password = input.getText().toString();
                    List<Uploader.Item> retryOnly = new ArrayList<>();
                    retryOnly.add(fTarget);
                    status.setText("جاري الرفع...");
                    uploadItems(dest, retryOnly);
                })
                .setNegativeButton("تجاهل الملف ده", (d, w) -> {
                    List<String> remaining = new ArrayList<>(needPwNames);
                    remaining.remove(0);
                    if (remaining.isEmpty()) {
                        String finalMsg = totalAdded > 0 ? "تم رفع " + totalAdded + " ملف بنجاح" : "اتلغى";
                        status.setText(finalMsg);
                        if (totalAdded > 0) status.postDelayed(this::finish, 900);
                        else setButtonsEnabled(true);
                    } else {
                        askPasswordAndRetry(dest, allSent, remaining);
                    }
                })
                .show();
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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(NAVY);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("⚡ الملف ده يتحط فين؟");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        card.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setAlpha(0.85f);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(6), dp(6), dp(6), dp(14));
        card.addView(status);

        card.addView(makeButton("📎 ملف إحالة", GOLD, NAVY, "ref"));
        card.addView(makeButton("📄 ملف تسجيل (يضاف على الداتا)", Color.parseColor("#2c3750"), Color.WHITE, "main"));
        card.addView(makeButton("🛡️ ملف تشيك (يستبدل اللي قبله)", Color.parseColor("#0d9488"), Color.WHITE, "check"));

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.addView(root);
        setContentView(scroll);
    }

    private Button makeButton(String text, int bgColor, int textColor, final String dest) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
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
