package com.jvust.flowsave;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(10, 17, 33);
    private static final int PANEL = Color.rgb(20, 30, 49);
    private static final int TEXT = Color.rgb(238, 244, 252);
    private static final int MUTED = Color.rgb(148, 165, 190);
    private static final int ACCENT = Color.rgb(66, 210, 190);
    private static final int OUTLINE = Color.rgb(46, 62, 82);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText linkBox;
    private TextView statusText, previewText, progressText;
    private ProgressBar progressBar;
    private Button checkButton, downloadButton, openButton;
    private RadioGroup formats;
    private Uri latestUri;
    private boolean busy;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        render();
        Intent initial = getIntent();
        if (Intent.ACTION_SEND.equals(initial.getAction()) && "text/plain".equals(initial.getType())) {
            String url = initial.getStringExtra(Intent.EXTRA_TEXT);
            if (url != null) linkBox.setText(url);
        }
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView label(String content, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams itemParams(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }

    private Button button(String text, boolean filled) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(filled ? BG : ACCENT);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(shape(filled ? ACCENT : PANEL, 12, filled ? 0 : OUTLINE));
        b.setMinHeight(dp(49));
        b.setMinimumHeight(dp(49));
        return b;
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(22), dp(34), dp(22), dp(30));
        scroll.addView(main);
        setContentView(scroll);

        TextView tiny = label("FLOW / SAVE   ·   ANDROID", 12, ACCENT, true);
        tiny.setLetterSpacing(0.18f);
        main.addView(tiny);

        TextView title = label("喜欢的视频，\n收藏到手机。", 30, TEXT, true);
        title.setLineSpacing(dp(4), 1f);
        main.addView(title, itemParams(15));

        TextView sub = label("基于 yt-dlp · 简洁的视频与音频下载工具", 13, MUTED, false);
        main.addView(sub, itemParams(10));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(17), dp(18), dp(17), dp(20));
        panel.setBackground(shape(PANEL, 18, OUTLINE));
        main.addView(panel, itemParams(28));

        panel.addView(label("01   视频链接", 14, TEXT, true));
        LinearLayout linkRow = new LinearLayout(this);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        linkBox = new EditText(this);
        linkBox.setSingleLine(true);
        linkBox.setSelectAllOnFocus(false);
        linkBox.setTextColor(TEXT);
        linkBox.setHintTextColor(MUTED);
        linkBox.setTextSize(14);
        linkBox.setHint("粘贴 https:// 视频地址");
        linkBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkBox.setPadding(dp(12), 0, dp(8), 0);
        linkBox.setBackground(shape(BG, 11, OUTLINE));
        LinearLayout.LayoutParams inputP = new LinearLayout.LayoutParams(0, dp(52), 1f);
        linkRow.addView(linkBox, inputP);
        Button paste = button("粘贴", false);
        LinearLayout.LayoutParams pasteP = new LinearLayout.LayoutParams(dp(72), dp(52));
        pasteP.leftMargin = dp(8);
        linkRow.addView(paste, pasteP);
        paste.setOnClickListener(v -> pasteClipboard());
        panel.addView(linkRow, itemParams(12));

        panel.addView(label("02   保存格式", 14, TEXT, true), itemParams(24));
        formats = new RadioGroup(this);
        formats.setOrientation(1);
        RadioButton video = formatRadio("视频 · MP4 优先（免合并）", 1);
        RadioButton audio = formatRadio("音频 · M4A 优先（原始格式）", 2);
        formats.addView(video);
        formats.addView(audio);
        formats.check(1);
        panel.addView(formats, itemParams(10));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        checkButton = button("解析信息", false);
        downloadButton = button("开始下载  ↓", true);
        LinearLayout.LayoutParams smallP = new LinearLayout.LayoutParams(0, dp(54), 0.42f);
        LinearLayout.LayoutParams bigP = new LinearLayout.LayoutParams(0, dp(54), 0.58f);
        bigP.leftMargin = dp(10);
        actions.addView(checkButton, smallP);
        actions.addView(downloadButton, bigP);
        panel.addView(actions, itemParams(22));
        checkButton.setOnClickListener(v -> runAction(false));
        downloadButton.setOnClickListener(v -> runAction(true));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(17), dp(18), dp(17), dp(17));
        info.setBackground(shape(PANEL, 18, OUTLINE));
        main.addView(info, itemParams(18));
        info.addView(label("03   下载状态", 14, TEXT, true));
        statusText = label("准备就绪 · 支持可直接下载的媒体流", 13, MUTED, false);
        statusText.setMaxLines(5);
        info.addView(statusText, itemParams(11));
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(OUTLINE));
        info.addView(progressBar, itemParams(16));
        progressText = label("0%", 12, ACCENT, true);
        info.addView(progressText, itemParams(6));
        previewText = label("解析后将显示视频标题和作者", 12, MUTED, false);
        previewText.setMaxLines(5);
        info.addView(previewText, itemParams(14));
        openButton = button("打开已保存文件", false);
        openButton.setEnabled(false);
        openButton.setAlpha(0.5f);
        info.addView(openButton, itemParams(14));
        openButton.setOnClickListener(v -> openLast());

        TextView foot = label("仅保存你有权下载的内容。部分平台需要登录、FFmpeg 或额外验证，本版本不支持 DRM 绕过与格式转码。", 12, MUTED, false);
        foot.setLineSpacing(dp(3), 1.05f);
        main.addView(foot, itemParams(23));
    }

    private RadioButton formatRadio(String text, int id) {
        RadioButton b = new RadioButton(this);
        b.setId(id);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        b.setPadding(dp(4), dp(5), dp(4), dp(5));
        b.setButtonTintList(ColorStateList.valueOf(ACCENT));
        return b;
    }

    private void pasteClipboard() {
        ClipboardManager clip = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clip != null && clip.hasPrimaryClip()) {
            ClipData data = clip.getPrimaryClip();
            if (data != null && data.getItemCount() > 0) {
                CharSequence value = data.getItemAt(0).coerceToText(this);
                if (value != null) linkBox.setText(value.toString().trim());
            }
        }
    }

    private void setBusy(boolean active) {
        busy = active;
        downloadButton.setEnabled(!active);
        checkButton.setEnabled(!active);
        downloadButton.setAlpha(active ? 0.55f : 1f);
        checkButton.setAlpha(active ? 0.55f : 1f);
    }

    private void status(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    /** Called by Python's progress hook from a worker thread. */
    public void onProgress(int percentage, String message) {
        runOnUiThread(() -> {
            progressBar.setProgress(Math.max(0, Math.min(100, percentage)));
            progressText.setText(Math.max(0, Math.min(100, percentage)) + "%");
            statusText.setText(message);
        });
    }

    private void runAction(boolean downloading) {
        if (busy) return;
        String url = linkBox.getText().toString().trim();
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            status("请输入有效的 http:// 或 https:// 视频链接");
            return;
        }
        setBusy(true);
        if (downloading) {
            progressBar.setProgress(0);
            progressText.setText("0%");
            openButton.setEnabled(false);
            openButton.setAlpha(0.5f);
        }
        status(downloading ? "正在准备下载引擎…" : "正在解析视频信息…");
        final String formatChoice = formats.getCheckedRadioButtonId() == 2 ? "audio" : "video";
        worker.execute(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
                PyObject bridge = Python.getInstance().getModule("bridge");
                JSONObject response;
                if (downloading) {
                    File external = getExternalFilesDir("incoming");
                    File stage = external != null ? external : new File(getFilesDir(), "incoming");
                    if (!stage.exists() && !stage.mkdirs()) throw new IOException("无法创建临时目录");
                    response = new JSONObject(bridge.callAttr("download", url, formatChoice, stage.getAbsolutePath(), this).toString());
                } else {
                    response = new JSONObject(bridge.callAttr("probe", url).toString());
                }
                if (response.has("error")) {
                    status("失败：" + response.optString("error"));
                    return;
                }
                if (downloading) {
                    File result = new File(response.getString("path"));
                    if (!result.exists() || !result.isFile()) throw new IOException("找不到输出文件");
                    Uri publicUri = publishDownload(result);
                    latestUri = publicUri;
                    status(publicUri == null ? "下载成功：" + result.getAbsolutePath() : "完成！已保存到 下载 / FlowSave / " + result.getName());
                    onProgress(100, publicUri == null ? "文件已保存到应用目录" : "下载完成 · 已保存到系统下载目录");
                    runOnUiThread(() -> {
                        previewText.setText(result.getName());
                        if (publicUri != null) {
                            openButton.setEnabled(true);
                            openButton.setAlpha(1f);
                        }
                    });
                } else {
                    String title = response.optString("title", "未命名视频");
                    String uploader = response.optString("uploader", "");
                    int duration = response.optInt("duration", 0);
                    String suffix = duration > 0 ? String.format(Locale.ROOT, "%d:%02d", duration / 60, duration % 60) : "时长未知";
                    runOnUiThread(() -> previewText.setText(title + "\n" + uploader + "  ·  " + suffix));
                    status("解析成功，选择格式后点击开始下载");
                }
            } catch (Exception error) {
                status("出现错误：" + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()));
            } finally {
                runOnUiThread(() -> setBusy(false));
            }
        });
    }

    private Uri publishDownload(File file) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, guessMime(file.getName()));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FlowSave");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("系统下载目录不可写");
        try {
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("无法打开系统下载目录");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            // Staging data is only removed after successful publication.
            if (!file.delete()) file.deleteOnExit();
            return uri;
        } catch (Exception error) {
            getContentResolver().delete(uri, null, null);
            throw error instanceof IOException ? (IOException) error : new IOException(error);
        }
    }

    private String guessMime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".opus")) return "audio/opus";
        return "application/octet-stream";
    }

    private void openLast() {
        if (latestUri == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(latestUri, getContentResolver().getType(latestUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(intent); }
        catch (Exception error) { status("文件已保存，请到系统“下载”文件夹打开"); }
    }

    @Override public void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
