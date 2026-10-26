package com.whalepet;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int MIN_HOVER = 120, MAX_HOVER = 480;
    private static final int MIN_PERCH = 56, MAX_PERCH = 160;
    private static final int MIN_AMP = 30, MAX_AMP = 150;      // 百分比

    private SharedPreferences prefs;
    private TextView tvStatus, tvHover, tvPerch, tvAmp;
    private SeekBar sbHover, sbPerch, sbAmp;
    private Button btnPerm, btnToggle, btnFace, btnSkin, btnDuck;
    private Switch swSound, swWander;
    private LinearLayout llLines;
    private EditText etNewLine;
    private Button btnBubbleLight, btnBubbleDark, btnAddLine, btnResetLines;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PetService.PREFS, MODE_PRIVATE);

        tvStatus = findViewById(R.id.tvStatus);
        tvHover = findViewById(R.id.tvHover);
        tvPerch = findViewById(R.id.tvPerch);
        tvAmp = findViewById(R.id.tvAmp);
        sbHover = findViewById(R.id.sbHover);
        sbPerch = findViewById(R.id.sbPerch);
        sbAmp = findViewById(R.id.sbAmp);
        btnPerm = findViewById(R.id.btnPerm);
        btnToggle = findViewById(R.id.btnToggle);
        btnFace = findViewById(R.id.btnFace);
        btnFace.setOnClickListener(v -> sendAction(PetService.ACTION_NEXT_FACE));

        btnDuck = findViewById(R.id.btnDuck);
        btnDuck.setOnClickListener(v -> {
            int k = (prefs.getInt(PetService.KEY_DUCK, 0) + 1) % PetService.DUCK_COUNT;
            prefs.edit().putInt(PetService.KEY_DUCK, k).apply();
            btnDuck.setText(duckLabel(k));
            refreshService();
            sendAction(PetService.ACTION_PREVIEW_DUCK);   // 点一下立刻试听
        });


        llLines = findViewById(R.id.llLines);
        etNewLine = findViewById(R.id.etNewLine);
        btnBubbleLight = findViewById(R.id.btnBubbleLight);
        btnBubbleDark = findViewById(R.id.btnBubbleDark);
        btnAddLine = findViewById(R.id.btnAddLine);
        btnResetLines = findViewById(R.id.btnResetLines);

        btnBubbleLight.setOnClickListener(v -> {
            prefs.edit().putInt(PetService.KEY_BUBBLE_STYLE, 0).apply();
            syncBubbleUi();
            sendAction(PetService.ACTION_REFRESH);
        });
        btnBubbleDark.setOnClickListener(v -> {
            prefs.edit().putInt(PetService.KEY_BUBBLE_STYLE, 1).apply();
            syncBubbleUi();
            sendAction(PetService.ACTION_REFRESH);
        });
        btnAddLine.setOnClickListener(v -> addLine());
        btnResetLines.setOnClickListener(v -> {
            prefs.edit().remove(PetService.KEY_LINES).apply();
            renderLines();
            Toast.makeText(this, "已恢复默认台词", Toast.LENGTH_SHORT).show();
        });

        btnSkin = findViewById(R.id.btnSkin);
        btnSkin.setOnClickListener(v -> {
            int n = (prefs.getInt(PetService.KEY_SKIN, 0) + 1) % PetView.SKINS.length;
            prefs.edit().putInt(PetService.KEY_SKIN, n).apply();
            btnSkin.setText(skinLabel(n));
            refreshService();
        });

        swWander = findViewById(R.id.swWander);
        swWander.setOnCheckedChangeListener((sw, checked) -> {
            prefs.edit().putBoolean(PetService.KEY_WANDER, checked).apply();
            refreshService();
        });

        swSound = findViewById(R.id.swSound);
        swSound.setOnCheckedChangeListener((sw, checked) -> {
            prefs.edit().putBoolean(PetService.KEY_SOUND, checked).apply();
            refreshService();
        });

        sbHover.setMax(MAX_HOVER - MIN_HOVER);
        sbPerch.setMax(MAX_PERCH - MIN_PERCH);
        sbAmp.setMax(MAX_AMP - MIN_AMP);

        btnPerm.setOnClickListener(v -> requestOverlay());
        btnToggle.setOnClickListener(v -> togglePet());

        sbHover.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int v = MIN_HOVER + p;
                prefs.edit().putFloat(PetService.KEY_HOVER_H, v).apply();
                tvHover.setText("悬空大小：" + v + " dp");
                refreshService();
            }
        });
        sbPerch.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int v = MIN_PERCH + p;
                prefs.edit().putFloat(PetService.KEY_PERCH_W, v).apply();
                tvPerch.setText("趴边时脑袋宽度：" + v + " dp");
                refreshService();
            }
        });
        sbAmp.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int pct = MIN_AMP + p;
                prefs.edit().putFloat(PetService.KEY_AMP, pct / 100f).apply();
                tvAmp.setText("动态幅度：" + pct + "%");
                refreshService();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 我们自己的界面在前台时，先把她收起来 —— 否则她会盖在设置页上面
        sendBool(PetService.ACTION_SET_HIDDEN, PetService.EXTRA_HIDDEN, true);
        syncUi();
    }

    @Override
    protected void onPause() {
        // 离开设置页就把她放回来
        sendBool(PetService.ACTION_SET_HIDDEN, PetService.EXTRA_HIDDEN, false);
        super.onPause();
    }

    private void syncBubbleUi() {
        int style = prefs.getInt(PetService.KEY_BUBBLE_STYLE, 0);
        if (btnBubbleLight != null) btnBubbleLight.setText(style == 0 ? "● 白色气泡" : "白色气泡");
        if (btnBubbleDark != null) btnBubbleDark.setText(style == 1 ? "● 深色气泡" : "深色气泡");
        renderLines();
    }

    private ArrayList<String> loadLines() {
        ArrayList<String> out = new ArrayList<>();
        String raw = prefs.getString(PetService.KEY_LINES, "");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        if (out.isEmpty()) {
            for (String line : PetService.DEFAULT_LINES) out.add(line);
        }
        return out;
    }

    private void persistLines(ArrayList<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        prefs.edit().putString(PetService.KEY_LINES, sb.toString()).apply();
    }

    private void renderLines() {
        if (llLines == null) return;
        llLines.removeAllViews();
        final ArrayList<String> lines = loadLines();
        for (int i = 0; i < lines.size(); i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView tv = new TextView(this);
            tv.setText(lines.get(i));
            tv.setTextSize(13);
            tv.setTextColor(getColor(R.color.ink));
            tv.setPadding(dp(8), dp(7), dp(8), dp(7));
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);

            Button del = new Button(this);
            del.setText("删");
            del.setAllCaps(false);
            del.setTextSize(12);
            del.setTextColor(getColor(R.color.ink));
            del.setBackgroundResource(R.drawable.btn_ghost);
            del.setMinWidth(0);
            del.setMinimumWidth(0);
            del.setPadding(dp(10), 0, dp(10), 0);
            del.setOnClickListener(v -> {
                ArrayList<String> cur = loadLines();
                if (cur.size() <= 1) {
                    Toast.makeText(this, "至少留一句", Toast.LENGTH_SHORT).show();
                    return;
                }
                cur.remove(idx);
                persistLines(cur);
                renderLines();
            });
            row.addView(del);

            llLines.addView(row);
        }
    }

    private void addLine() {
        String text = etNewLine.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "先写一句台词", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> lines = loadLines();
        lines.add(text);
        persistLines(lines);
        etNewLine.setText("");
        renderLines();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String duckLabel(int idx) {
        return "音效 " + (idx + 1) + " / " + PetService.DUCK_COUNT + "　▸ 点击试听";
    }

    private String skinLabel(int idx) {
        return "皮肤：" + PetView.SKINS[idx % PetView.SKINS.length].name + "　▸ 点击切换";
    }

    private void sendBool(String action, String key, boolean value) {
        if (!prefs.getBoolean(PetService.KEY_RUNNING, false)) return;
        Intent i = new Intent(this, PetService.class).setAction(action).putExtra(key, value);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    private void syncUi() {
        float hh = prefs.getFloat(PetService.KEY_HOVER_H, PetService.DEFAULT_HOVER_H);
        float pw = prefs.getFloat(PetService.KEY_PERCH_W, PetService.DEFAULT_PERCH_W);
        float amp = prefs.getFloat(PetService.KEY_AMP, PetService.DEFAULT_AMP);
        sbHover.setProgress(Math.round(hh) - MIN_HOVER);
        sbPerch.setProgress(Math.round(pw) - MIN_PERCH);
        int ampPct = Math.round(amp * 100);
        sbAmp.setProgress(Math.max(0, Math.min(MAX_AMP - MIN_AMP, ampPct - MIN_AMP)));
        tvHover.setText("悬空大小：" + Math.round(hh) + " dp");
        tvPerch.setText("趴边时脑袋宽度：" + Math.round(pw) + " dp");
        tvAmp.setText("动态幅度：" + ampPct + "%");

        btnDuck.setText(duckLabel(prefs.getInt(PetService.KEY_DUCK, 0)));
        boolean snd = prefs.getBoolean(PetService.KEY_SOUND, true);
        if (swSound.isChecked() != snd) swSound.setChecked(snd);   // 先判断再设，避免回调绕圈
        boolean wdr = prefs.getBoolean(PetService.KEY_WANDER, true);
        if (swWander.isChecked() != wdr) swWander.setChecked(wdr);

        int ski = prefs.getInt(PetService.KEY_SKIN, 0);
        btnSkin.setText(skinLabel(ski));

        syncBubbleUi();

        boolean overlay = hasOverlay();
        boolean running = prefs.getBoolean(PetService.KEY_RUNNING, false);
        btnPerm.setEnabled(!overlay);
        btnPerm.setText(overlay ? "① 权限已授予 ✓" : "① 授予「显示在其他应用上层」");
        btnToggle.setText(running ? "④ 收回她" : "② 让她出现");
        PetView.Skin skin = PetView.SKINS[ski % PetView.SKINS.length];
        int faces = countFaces(ski % PetView.SKINS.length);
        tvStatus.setText((overlay
                ? (running ? "状态：正在陪你。拖到屏幕左右边缘松手，她会扒在边上。" : "状态：已就绪，点下面的按钮让她出现。")
                : "状态：还差一步 —— 需要「显示在其他应用上层」权限，否则她没法浮在别的应用上面。")
                + "\n当前皮肤：" + skin.name + " · 表情 " + faces + " / " + skin.faces.length
                + (faces < skin.faces.length ? "（素材缺失，重新装一次）" : "")
                + "\n（打开这个界面时她会自动收起，免得挡住设置；回到桌面就回来）");
    }

    /** 数当前皮肤真正能加载到的表情数（路径来自 PetView.SKINS，不再写死） */
    private int countFaces(int skinIdx) {
        int n = 0;
        for (String f : PetView.SKINS[skinIdx].faces) {
            try {
                getAssets().open(f).close();
                n++;
            } catch (Exception ignored) { }
        }
        return n;
    }

    private boolean hasOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "请手动到「设置 → 应用 → 显示在其他应用上层」里打开",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void togglePet() {
        boolean running = prefs.getBoolean(PetService.KEY_RUNNING, false);
        Intent i = new Intent(this, PetService.class);
        if (running) {
            i.setAction(PetService.ACTION_HIDE);
        } else {
            if (!hasOverlay()) {
                Toast.makeText(this, "先授予「显示在其他应用上层」权限", Toast.LENGTH_SHORT).show();
                requestOverlay();
                return;
            }
            i.setAction(PetService.ACTION_SHOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        tvStatus.postDelayed(this::syncUi, 400);
    }

    private void refreshService() {
        if (!prefs.getBoolean(PetService.KEY_RUNNING, false)) return;
        sendAction(PetService.ACTION_REFRESH);
    }

    private void sendAction(String action) {
        Intent i = new Intent(this, PetService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }
}
