package com.jvust.flowsave;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native Android interface with embedded yt-dlp/Chaquopy download engine. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(9, 17, 31);
    private static final int SURFACE = Color.rgb(20, 32, 50);
    private static final int INPUT = Color.rgb(12, 23, 39);
    private static final int STROKE = Color.rgb(43, 61, 80);
    private static final int LIGHT = Color.rgb(243, 248, 250);
    private static final int MUTED = Color.rgb(150, 168, 187);
    private static final int AQUA = Color.rgb(135, 240, 207);
    private static final int LAVENDER = Color.rgb(181, 173, 250);
    private static final int SOFT = Color.rgb(28, 65, 64);
    private static final int COOKIE_PICKER = 220;
    private static final int STORAGE_PERMISSION = 221;
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"'\\u201c\\u201d\\u300b\\u3011]+", Pattern.CASE_INSENSITIVE);
    private static final String[] QUALITY_IDS = {"best", "1080", "720", "audio"};
    private static final String[] QUALITY_LABELS = {"最佳画质", "高清 1080P", "流畅 720P", "仅音频"};
    private static final String[] SITES = {"YouTube", "X / Twitter", "TikTok", "Instagram", "抖音", "Bilibili"};
    private static final String[] SITE_MARKS = {"▶", "𝕏", "♪", "◎", "♫", "▣"};
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    private EditText linkBox;
    private TextView linkStatus, statusText, previewTitle, previewSub, progressLabel, cookieStatus;
    private ProgressBar progressBar;
    private Button analyzeButton, downloadButton, cancelButton, openButton, importButton;
    private TextView[] qualityViews = new TextView[4];
    private String selectedQuality = "best";
    private Uri latestUri;
    private volatile boolean busy;
    private volatile boolean cancelRequested;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        render();
        acceptIntent(getIntent());
        if (state != null) {
            linkBox.setText(state.getString("url", linkBox.getText().toString()));
            selectQuality(state.getString("quality", "best"));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptIntent(intent);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("url", linkBox.getText().toString());
        state.putString("quality", selectedQuality);
        super.onSaveInstanceState(state);
    }

    private void acceptIntent(Intent intent) {
        if (intent == null) return;
        String shared = null;
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            shared = intent.getDataString();
        }
        if (shared != null) linkBox.setText(shared.trim());
    }

    private int dp(float n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    private GradientDrawable panel(int bg, float radius, int outline) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(radius));
        if (outline != 0) gd.setStroke(dp(1), outline);
        return gd;
    }

    private GradientDrawable gradient(float radius) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{AQUA, Color.rgb(158, 222, 228), LAVENDER});
        gd.setCornerRadius(dp(radius));
        return gd;
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setFontFeatureSettings("kern");
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private Button action(String label, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(primary ? INPUT : LIGHT);
        b.setBackground(primary ? gradient(15) : panel(INPUT, 15, STROKE));
        b.setMinHeight(dp(52));
        b.setMinimumHeight(dp(52));
        return b;
    }

    private LinearLayout card(int pad) {
        LinearLayout content = vertical();
        content.setPadding(dp(pad), dp(pad + 2), dp(pad), dp(pad + 2));
        content.setBackground(panel(SURFACE, 21, STROKE));
        return content;
    }

    private TextView section(String number, String title) {
        TextView t = text(number + "     " + title, 15, LIGHT, true);
        t.setLetterSpacing(0.01f);
        return t;
    }

    private TextView smallPill(String value) {
        TextView t = text(value, 11, AQUA, true);
        t.setPadding(dp(13), dp(7), dp(13), dp(7));
        t.setBackground(panel(SOFT, 12, 0));
        return t;
    }

    private void render() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BG);
        scroller.setClipToPadding(false);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroller.addView(body);
        setContentView(scroller);

        // Brand bar
        LinearLayout header = horizontal();
        TextView brandIcon = text("↘", 26, INPUT, true);
        brandIcon.setGravity(Gravity.CENTER);
        brandIcon.setBackground(gradient(15));
        header.addView(brandIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout brandTexts = vertical();
        brandTexts.addView(text("FlowSave", 19, LIGHT, true));
        brandTexts.addView(text("流影下载  /  轻松保存每一帧", 10, MUTED, false));
        LinearLayout.LayoutParams brandP = new LinearLayout.LayoutParams(0, -2, 1f);
        brandP.leftMargin = dp(12);
        header.addView(brandTexts, brandP);
        TextView version = smallPill("V 0.2");
        header.addView(version);
        body.addView(header);

        TextView kicker = text("YOUR MEDIA · YOUR MOMENTS", 11, AQUA, true);
        kicker.setLetterSpacing(0.13f);
        body.addView(kicker, marginTop(29));
        TextView heading = text("喜欢的瞬间，\n随时带走。", 34, LIGHT, true);
        heading.setLineSpacing(dp(5), 1f);
        body.addView(heading, marginTop(10));
        TextView intro = text("粘贴链接，选择画质，保存到手机。\n跨平台下载，也可以从其他 App 直接分享链接。", 13, MUTED, false);
        intro.setLineSpacing(dp(3), 1f);
        body.addView(intro, marginTop(12));

        TextView supportTitle = text("支持的平台", 12, MUTED, true);
        body.addView(supportTitle, marginTop(26));
        for (int row = 0; row < 2; row++) {
            LinearLayout strip = horizontal();
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                TextView chip = text(SITE_MARKS[index] + "  " + SITES[index], 11, LIGHT, true);
                chip.setGravity(Gravity.CENTER);
                chip.setSingleLine(true);
                chip.setBackground(panel(INPUT, 11, STROKE));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(41), 1f);
                if (col != 0) cp.leftMargin = dp(7);
                strip.addView(chip, cp);
            }
            body.addView(strip, marginTop(row == 0 ? 10 : 7));
        }

        // Link entry
        LinearLayout linkCard = card(18);
        body.addView(linkCard, marginTop(25));
        linkCard.addView(section("01", "视频或分享链接"));
        linkCard.addView(text("支持直接粘贴抖音 / TikTok 等分享文案", 12, MUTED, false), marginTop(6));
        LinearLayout linkRow = horizontal();
        linkBox = new EditText(this);
        linkBox.setTextColor(LIGHT);
        linkBox.setHintTextColor(MUTED);
        linkBox.setHint("粘贴 https:// 链接…");
        linkBox.setSingleLine(true);
        linkBox.setTextSize(13);
        linkBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkBox.setPadding(dp(12), 0, dp(7), 0);
        linkBox.setBackground(panel(INPUT, 12, STROKE));
        linkRow.addView(linkBox, new LinearLayout.LayoutParams(0, dp(54), 1f));
        Button paste = action("粘贴", false);
        LinearLayout.LayoutParams pasteP = new LinearLayout.LayoutParams(dp(68), dp(54));
        pasteP.leftMargin = dp(8);
        linkRow.addView(paste, pasteP);
        paste.setOnClickListener(v -> pasteFromClipboard());
        linkCard.addView(linkRow, marginTop(15));
        linkStatus = text("自动识别平台 · 只在手机上处理链接", 12, AQUA, false);
        linkCard.addView(linkStatus, marginTop(10));
        linkBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String url = extractUrl(s.toString());
                linkStatus.setText(url == null ? "粘贴链接后自动识别" :
                        "✓ 已识别：" + siteOf(url) + " · 可点击解析");
            }
            public void afterTextChanged(Editable e) { }
        });

        // Format control
        LinearLayout formatCard = card(18);
        body.addView(formatCard, marginTop(14));
        formatCard.addView(section("02", "选择保存格式"));
        formatCard.addView(text("自动挑选可用格式，必要时在手机内合并音视频", 12, MUTED, false), marginTop(6));
        for (int row = 0; row < 2; row++) {
            LinearLayout chipRow = horizontal();
            for (int col = 0; col < 2; col++) {
                final int idx = row * 2 + col;
                TextView q = text(QUALITY_LABELS[idx], 13, LIGHT, true);
                q.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams qP = new LinearLayout.LayoutParams(0, dp(49), 1f);
                if (col != 0) qP.leftMargin = dp(9);
                chipRow.addView(q, qP);
                qualityViews[idx] = q;
                q.setOnClickListener(v -> selectQuality(QUALITY_IDS[idx]));
            }
            formatCard.addView(chipRow, marginTop(row == 0 ? 15 : 9));
        }
        selectQuality("best");

        // Cookies are kept locally in this app's private storage.
        LinearLayout authCard = card(18);
        body.addView(authCard, marginTop(14));
        authCard.addView(section("03", "需要登录的视频？"));
        authCard.addView(text("可导入 Netscape 格式 Cookies 文件；只保存在本机，\n不会传给 FlowSave 服务器（应用没有服务器）。", 12, MUTED, false), marginTop(8));
        LinearLayout cookieRow = horizontal();
        cookieStatus = text(cookieFile().isFile() ? "✓ 已导入 Cookies" : "当前：未导入", 12, AQUA, true);
        cookieRow.addView(cookieStatus, new LinearLayout.LayoutParams(0, -2, 1f));
        importButton = action("导入 Cookies", false);
        cookieRow.addView(importButton, new LinearLayout.LayoutParams(dp(128), dp(46)));
        authCard.addView(cookieRow, marginTop(13));
        importButton.setOnClickListener(v -> chooseCookies());
        TextView clearCookies = text("清除已导入的 Cookies  ›", 12, MUTED, false);
        clearCookies.setGravity(Gravity.RIGHT);
        authCard.addView(clearCookies, marginTop(9));
        clearCookies.setOnClickListener(v -> {
            if (busy) { status("请在当前任务结束后清除 Cookies"); return; }
            if (cookieFile().exists() && !cookieFile().delete()) {
                status("无法删除 Cookies，请在系统设置中清除应用数据");
            } else {
                cookieStatus.setText("当前：未导入");
                status("已从手机应用私有目录删除 Cookies");
            }
        });

        // Action area
        LinearLayout actions = horizontal();
        analyzeButton = action("解析视频", false);
        downloadButton = action("立即下载  ↓", true);
        actions.addView(analyzeButton, new LinearLayout.LayoutParams(0, dp(57), 0.42f));
        LinearLayout.LayoutParams dP = new LinearLayout.LayoutParams(0, dp(57), 0.58f);
        dP.leftMargin = dp(9);
        actions.addView(downloadButton, dP);
        body.addView(actions, marginTop(20));
        analyzeButton.setOnClickListener(v -> runAction(false));
        downloadButton.setOnClickListener(v -> runAction(true));

        // Preview and task status
        LinearLayout result = card(18);
        body.addView(result, marginTop(16));
        LinearLayout resultBar = horizontal();
        TextView taskTitle = text("下载工作台", 15, LIGHT, true);
        resultBar.addView(taskTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView liveTag = smallPill("LOCAL FIRST");
        resultBar.addView(liveTag);
        result.addView(resultBar);
        previewTitle = text("等待你的第一个链接", 17, LIGHT, true);
        previewTitle.setMaxLines(3);
        result.addView(previewTitle, marginTop(18));
        previewSub = text("解析后将在这里显示标题、发布者与时长", 12, MUTED, false);
        previewSub.setMaxLines(4);
        result.addView(previewSub, marginTop(8));
        statusText = text("引擎就绪 · 使用最新打包的 yt-dlp", 12, MUTED, false);
        statusText.setMaxLines(5);
        result.addView(statusText, marginTop(18));
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressTintList(ColorStateList.valueOf(AQUA));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(STROKE));
        result.addView(progressBar, marginTop(12));
        progressLabel = text("准备开始 · 0%", 12, AQUA, true);
        result.addView(progressLabel, marginTop(7));
        LinearLayout bottom = horizontal();
        openButton = action("打开文件 ↗", false);
        openButton.setEnabled(false);
        openButton.setAlpha(0.38f);
        bottom.addView(openButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        cancelButton = action("取消任务", false);
        cancelButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams cP = new LinearLayout.LayoutParams(0, dp(48), 1f);
        cP.leftMargin = dp(9);
        bottom.addView(cancelButton, cP);
        result.addView(bottom, marginTop(17));
        openButton.setOnClickListener(v -> openLast());
        cancelButton.setOnClickListener(v -> {
            cancelRequested = true;
            status("已请求取消，正在结束当前下载…");
        });

        TextView footer = text("仅用于保存你有权下载的内容。平台可能要求登录、地区验证或限制访问；不支持 DRM 绕过。下载时请保持此页面开启。", 11, MUTED, false);
        footer.setLineSpacing(dp(3), 1f);
        body.addView(footer, marginTop(21));
    }

    private void selectQuality(String id) {
        selectedQuality = id;
        for (int i = 0; i < qualityViews.length; i++) {
            TextView q = qualityViews[i];
            if (q == null) continue;
            boolean chosen = id.equals(QUALITY_IDS[i]);
            q.setTextColor(chosen ? AQUA : LIGHT);
            q.setBackground(panel(chosen ? SOFT : INPUT, 13, chosen ? AQUA : STROKE));
        }
    }

    private static String extractUrl(String input) {
        Matcher m = LINK.matcher(input == null ? "" : input);
        if (!m.find()) return null;
        String url = m.group().replaceAll("[\\.,;!?)\\uff0c\\u3002\\uff01\\uff1f\\uff09]+$", "");
        Uri uri = Uri.parse(url);
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null ? url : null;
    }

    private static String siteOf(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null) return "其他站点";
        host = host.toLowerCase(Locale.ROOT);
        if (hasDomain(host, "youtube.com") || hasDomain(host, "youtu.be")) return "YouTube";
        if (hasDomain(host, "x.com") || hasDomain(host, "twitter.com")) return "X / Twitter";
        if (hasDomain(host, "tiktok.com")) return "TikTok";
        if (hasDomain(host, "instagram.com")) return "Instagram";
        if (hasDomain(host, "douyin.com") || hasDomain(host, "iesdouyin.com")) return "抖音";
        if (hasDomain(host, "bilibili.com") || hasDomain(host, "b23.tv")) return "Bilibili";
        return "其他支持的网站";
    }

    private static boolean hasDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence value = clip.getItemAt(0).coerceToText(this);
            if (value != null) linkBox.setText(value.toString().trim());
        }
    }

    private File cookieFile() { return new File(getFilesDir(), "site_cookies.txt"); }

    private void chooseCookies() {
        if (busy) { status("下载任务结束后才能更换 Cookies"); return; }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, COOKIE_PICKER);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != COOKIE_PICKER || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            File dest = cookieFile();
            byte[] buffer = new byte[8192];
            long total = 0;
            try (InputStream in = getContentResolver().openInputStream(data.getData());
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new IOException("无法读取选中的文件");
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    total += n;
                    if (total > 2 * 1024 * 1024) throw new IOException("Cookies 文件不能超过 2 MB");
                    out.write(buffer, 0, n);
                }
            }
            if (total < 15 || !isNetscapeCookies(dest)) throw new IOException("请选择 Netscape cookies.txt 格式文件");
            cookieStatus.setText("✓ 已导入 Cookies（仅本机）");
            status("Cookies 导入成功，可再次尝试需要登录的内容");
        } catch (Exception e) {
            if (cookieFile().exists()) cookieFile().delete();
            cookieStatus.setText("当前：未导入");
            status("导入失败：" + e.getMessage());
        }
    }

    private boolean isNetscapeCookies(File file) throws IOException {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            for (int i = 0; i < 40 && (line = r.readLine()) != null; i++) {
                if (line.startsWith("# Netscape HTTP Cookie File") || line.startsWith("# HTTP Cookie File")) return true;
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.split("\t", -1).length == 7) return true;
            }
        }
        return false;
    }

    private void status(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private void setBusy(boolean enabled) {
        busy = enabled;
        analyzeButton.setEnabled(!enabled);
        downloadButton.setEnabled(!enabled);
        importButton.setEnabled(!enabled);
        analyzeButton.setAlpha(enabled ? .43f : 1f);
        downloadButton.setAlpha(enabled ? .43f : 1f);
        cancelButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Invoked by yt-dlp Python progress hook from the background thread. */
    public void onProgress(int percent, String message) {
        runOnUiThread(() -> {
            progressBar.setProgress(Math.max(0, Math.min(100, percent)));
            progressLabel.setText(percent + "%");
            statusText.setText(message);
        });
    }

    public boolean isCancelled() { return cancelRequested; }

    private void runAction(boolean downloading) {
        if (busy) return;
        String url = extractUrl(linkBox.getText().toString());
        if (url == null) { status("请输入有效的链接，可直接粘贴分享文案"); return; }
        if (downloading && Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION);
            return;
        }
        cancelRequested = false;
        setBusy(true);
        if (downloading) {
            latestUri = null;
            progressBar.setProgress(0);
            progressLabel.setText("正在准备 · 0%");
            openButton.setEnabled(false);
            openButton.setAlpha(.38f);
        }
        status(downloading ? "正在初始化下载引擎…" : "正在获取媒体信息…");
        final String quality = selectedQuality;
        background.execute(() -> {
            File stage = null;
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
                PyObject module = Python.getInstance().getModule("bridge");
                String cookie = cookieFile().exists() ? cookieFile().getAbsolutePath() : "";
                String qjs = new File(getApplicationInfo().nativeLibraryDir, "libqjs.so").getAbsolutePath();
                JSONObject response;
                if (downloading) {
                    File root = getExternalFilesDir("incoming");
                    if (root == null) root = new File(getCacheDir(), "incoming");
                    stage = new File(root, UUID.randomUUID().toString());
                    if (!stage.mkdirs()) throw new IOException("无法创建缓存文件夹");
                    response = new JSONObject(module.callAttr("download", url, quality,
                            stage.getAbsolutePath(), this, cookie, qjs).toString());
                } else {
                    response = new JSONObject(module.callAttr("probe", url, cookie, qjs).toString());
                }
                if (response.has("error")) {
                    status("操作失败：" + response.optString("error"));
                    return;
                }
                if (!downloading) {
                    String title = response.optString("title", "未命名视频");
                    String channel = response.optString("uploader", "未知作者");
                    String site = response.optString("site", siteOf(url));
                    int seconds = response.optInt("duration", 0);
                    String length = seconds > 0 ? String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60) : "时长未知";
                    runOnUiThread(() -> {
                        previewTitle.setText(title);
                        previewSub.setText(site + " · " + channel + " · " + length);
                    });
                    status("解析成功 · 选好画质后点击立即下载");
                    return;
                }
                if (cancelRequested) { status("下载已取消"); return; }
                File output;
                if ("mux".equals(response.optString("kind"))) {
                    File video = new File(response.getString("video_path"));
                    File audio = new File(response.getString("audio_path"));
                    output = new File(stage, safeName(response.optString("title", "FlowSave")) + ".mp4");
                    status("正在合并高清画面和音频…");
                    muxMedia(video, audio, output, false);
                } else if (response.optBoolean("extract_audio", false)) {
                    File source = new File(response.getString("path"));
                    output = new File(stage, "FlowSave-" + System.currentTimeMillis() + ".m4a");
                    status("正在从媒体文件中提取原始音轨…");
                    muxMedia(null, source, output, true);
                } else {
                    output = new File(response.getString("path"));
                }
                if (cancelRequested) { status("下载已取消"); return; }
                if (!output.isFile() || output.length() == 0) throw new IOException("文件不存在或为空");
                Uri outputUri = publishDownload(output);
                latestUri = outputUri;
                String filename = output.getName();
                runOnUiThread(() -> {
                    previewTitle.setText("已保存 · " + filename);
                    previewSub.setText("系统文件管理器 → Download / FlowSave");
                    openButton.setEnabled(outputUri != null);
                    openButton.setAlpha(outputUri != null ? 1f : .38f);
                });
                onProgress(100, "下载成功！已保存到 Download / FlowSave");
                progressLabel.post(() -> progressLabel.setText("100% · 下载完成"));
            } catch (Exception error) {
                String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                status(cancelRequested ? "下载已取消" : "保存失败：" + msg);
            } finally {
                if (stage != null) deleteRecursively(stage);
                runOnUiThread(() -> setBusy(false));
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != STORAGE_PERMISSION) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) runAction(true);
        else status("需要存储权限才能将视频保存到公共下载目录");
    }

    private static String safeName(String input) {
        String s = input.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        return s.isEmpty() ? "FlowSave" : s.substring(0, Math.min(65, s.length()));
    }

    /** Merge H.264 MP4 video + AAC M4A audio using built-in Android MediaMuxer.
     * No ffmpeg dependency is required for these compatible sources.
     */
    private void muxMedia(File video, File audio, File output, boolean audioOnly) throws IOException {
        MediaMuxer muxer = null;
        MediaExtractor ve = null;
        MediaExtractor ae = null;
        boolean started = false;
        boolean complete = false;
        try {
            if (!audioOnly) {
                ve = new MediaExtractor();
                ve.setDataSource(video.getAbsolutePath());
            }
            ae = new MediaExtractor();
            ae.setDataSource(audio.getAbsolutePath());
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoInput = -1, audioInput = -1, videoOutput = -1, audioOutput = -1;
            if (ve != null) {
                for (int i = 0; i < ve.getTrackCount(); i++) {
                    MediaFormat format = ve.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/") && videoInput < 0) {
                        videoInput = i;
                        videoOutput = muxer.addTrack(format);
                    }
                }
            }
            for (int i = 0; i < ae.getTrackCount(); i++) {
                MediaFormat format = ae.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/") && audioInput < 0) {
                    audioInput = i;
                    audioOutput = muxer.addTrack(format);
                }
            }
            if ((!audioOnly && videoInput < 0) || audioInput < 0) {
                throw new IOException("下载流不是可合并的 MP4 / AAC，请尝试其他画质");
            }
            muxer.start();
            started = true;
            if (!audioOnly) writeTrack(ve, videoInput, muxer, videoOutput);
            if (cancelRequested) throw new IOException("已取消下载");
            writeTrack(ae, audioInput, muxer, audioOutput);
            if (cancelRequested) throw new IOException("已取消下载");
            muxer.stop();
            started = false;
            complete = true;
        } catch (RuntimeException error) {
            throw new IOException("音视频合并失败，当前媒体编码可能不受手机支持：" + error.getMessage(), error);
        } finally {
            if (ve != null) ve.release();
            if (ae != null) ae.release();
            if (muxer != null) {
                if (started) { try { muxer.stop(); } catch (Exception ignored) { } }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (!complete && output.exists()) output.delete();
        }
    }

    private void writeTrack(MediaExtractor input, int inTrack, MediaMuxer muxer, int outTrack) throws IOException {
        input.selectTrack(inTrack);
        ByteBuffer bytes = ByteBuffer.allocateDirect(4 * 1024 * 1024);
        MediaCodec.BufferInfo buffer = new MediaCodec.BufferInfo();
        while (!cancelRequested) {
            bytes.clear();
            int size = input.readSampleData(bytes, 0);
            if (size < 0) break;
            buffer.offset = 0;
            buffer.size = size;
            buffer.presentationTimeUs = Math.max(0, input.getSampleTime());
            buffer.flags = input.getSampleFlags();
            muxer.writeSampleData(outTrack, bytes, buffer);
            if (!input.advance()) break;
        }
        input.unselectTrack(inTrack);
        if (cancelRequested) throw new IOException("已取消下载");
    }

    private Uri publishDownload(File source) throws IOException {
        String fileName = source.getName();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues info = new ContentValues();
            info.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            info.put(MediaStore.MediaColumns.MIME_TYPE, mime(fileName));
            info.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FlowSave");
            info.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, info);
            if (uri == null) throw new IOException("创建系统下载文件失败");
            try {
                try (InputStream in = new FileInputStream(source);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("系统未返回文件输出流");
                    copy(in, out);
                }
                ContentValues finish = new ContentValues();
                finish.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, finish, null, null);
                return uri;
            } catch (Exception e) {
                getContentResolver().delete(uri, null, null);
                throw e instanceof IOException ? (IOException)e : new IOException(e);
            }
        }
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FlowSave");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("无法创建 Download/FlowSave 文件夹");
        File out = new File(folder, fileName);
        if (out.exists()) out = new File(folder, System.currentTimeMillis() + "-" + fileName);
        try (InputStream in = new FileInputStream(source); OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        }
        // On Android 7-9 users can open the public file from their Downloads app.
        Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(out));
        sendBroadcast(scan);
        return null;
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) >= 0) {
            if (cancelRequested) throw new IOException("已取消下载");
            out.write(b, 0, n);
        }
    }

    private static void deleteRecursively(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        dir.delete();
    }

    private String mime(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        if (low.endsWith(".mp4") || low.endsWith(".m4v")) return "video/mp4";
        if (low.endsWith(".webm")) return "video/webm";
        if (low.endsWith(".m4a") || low.endsWith(".aac")) return "audio/mp4";
        if (low.endsWith(".mp3")) return "audio/mpeg";
        if (low.endsWith(".opus") || low.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }

    private void openLast() {
        if (latestUri == null) {
            status("视频已保存，请到系统“下载”文件夹找到 FlowSave");
            return;
        }
        Intent open = new Intent(Intent.ACTION_VIEW);
        String type = getContentResolver().getType(latestUri);
        open.setDataAndType(latestUri, type == null ? "*/*" : type);
        open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(open); }
        catch (Exception e) { status("请用系统文件管理器打开 Download / FlowSave"); }
    }

    @Override public void onDestroy() {
        cancelRequested = true;
        background.shutdownNow();
        super.onDestroy();
    }
}
