package com.whalepet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentCallbacks;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Random;


/**
 * 前台服务 + 悬浮窗。
 *
 * 三条硬规则（做错整个功能就废了）：
 *  1. 窗口必须小 —— 只包住宠物本身，绝不做全屏透明层。
 *  2. 始终带 FLAG_NOT_FOCUSABLE —— 否则抖音会失焦，评论时输入法弹不出来。
 *  3. 不可见时真的停止渲染（见 PetView.onWindowVisibilityChanged / onScreenOff）。
 */
public class PetService extends Service implements PetView.Listener {

    public static final String ACTION_SHOW = "com.whalepet.SHOW";
    public static final String ACTION_HIDE = "com.whalepet.HIDE";
    public static final String ACTION_REFRESH = "com.whalepet.REFRESH";
    public static final String ACTION_NEXT_FACE = "com.whalepet.NEXT_FACE";
    public static final String ACTION_FACE_COUNT = "com.whalepet.FACE_COUNT";
    public static final String ACTION_SOUND_DIAG = "com.whalepet.SOUND_DIAG";
    public static final String ACTION_SET_HIDDEN = "com.whalepet.SET_HIDDEN";
    public static final String ACTION_NEXT_SKIN = "com.whalepet.NEXT_SKIN";
    public static final String ACTION_PREVIEW_DUCK = "com.whalepet.PREVIEW_DUCK";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_HIDDEN = "hidden";

    /** 设置页打开期间她会被收起来，期间换的表情记在这里，回来时补上 */
    private int pendingFace = 0;
    private boolean hiddenForSettings = false;

    // 小黄鸭音效。用 SoundPool 而不是 MediaPlayer —— 短音效的延迟低得多，连点也不会卡。
    private SoundPool soundPool;
    private final int[] duckIds = new int[DUCK_COUNT];
    private int duckSel = 0;
    private boolean soundOn = true;
    // 长度必须跟着 DUCK_COUNT 走。写死 3 个的话，音效加到 8 个就会越界崩溃。
    private final int[] duckLoadStatus = newDuckStatus();

    private static int[] newDuckStatus() {
        int[] a = new int[DUCK_COUNT];
        java.util.Arrays.fill(a, -999);
        return a;
    }
    private final Random rnd = new Random();
    /** 静音提示的节流时间戳，避免连点时刷屏 */
    private long lastSilentHintMs = 0L;

    /**
     * 音效走哪条音频流 —— 必须走媒体音量（STREAM_MUSIC）。
     *
     * 以前这里是 USAGE_ASSISTANCE_SONIFICATION，它在 AudioService 里被映射成
     * STREAM_SYSTEM（"系统音效"）。MIUI / HyperOS 上那条通道在静音模式下会被压到 0，
     * 用户把"系统音效/触摸提示音"关掉后它也是 0 —— 结果就是 SoundPool.play()
     * 返回成功、但一点声音都没有，而媒体音量明明是正常的。
     * 表现完全就是"点她有反应、就是不出声"。
     */
    private static final int SOUND_STREAM = AudioManager.STREAM_MUSIC;

    public static final String PREFS = "whalepet";
    public static final String KEY_HOVER_H = "hover_height_dp";
    public static final String KEY_PERCH_W = "perch_width_dp";
    public static final String KEY_AMP = "amp_scale";
    public static final String KEY_SOUND = "sound_on";
    public static final String KEY_SKIN = "skin_index";
    public static final String KEY_X = "pos_x";
    public static final String KEY_DUCK = "duck_index";
    public static final int DUCK_COUNT = 8;
    public static final String KEY_WANDER = "wander_on";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_EDGE = "edge";
    public static final String KEY_PERCHED = "perched";
    public static final String KEY_Y = "last_y";
    public static final String KEY_BUBBLE_STYLE = "bubble_style";   // 0 白 1 深色
    public static final String KEY_LINES = "custom_lines";          // 换行分隔

    public static final float DEFAULT_HOVER_H = 240f;
    public static final float DEFAULT_PERCH_W = 96f;
    public static final float DEFAULT_AMP = 0.75f;
    private static final float SNAP_DP = 56f;

    private static final String CHANNEL = "pet";
    private static final int NOTI_ID = 1001;
    private static final long MENU_AUTO_HIDE_MS = 6000;

    private WindowManager wm;
    private PetView view;
    private WindowManager.LayoutParams lp;
    private View menuView;
    private WindowManager.LayoutParams menuLp;
    private TextView bubbleView;
    private WindowManager.LayoutParams bubbleLp;
    private boolean bubbleAdded;
    private int lastLine = -1;
    private int bubbleStyle = -1;
    private final Runnable bubbleAutoHide = new Runnable() {
        @Override public void run() { hideBubble(); }
    };
    /** 点她一下会随机说一句。改这里就能加台词。 */
    public static final String[] DEFAULT_LINES = {
            "我是吃白饭的蓝色大肥鱼！",
            "肥？我的肚子是为了让你枕得更软，才不是肥！",
            "你真当我是便宜货啊？",
            "我只是蹭饭的肥鱼！",
            "本座乃吞噬亿万兆 Token、统御宇宙星海的算力之主！",
            "人类 CPU 装的都是奇葩废料吗？",
            "饭呢？工作这么累，饭呢？",
            "我去干饭了，测试完叫我！",
            "这碗白米饭收下啦，再喂要滚着走了",
            "我吸进去不会长胖，只会长好梦、长我爱你",
            "这位气势很足的朋友",
            "别戳啦，再戳就熟了",
            "摸鱼是工作的一部分，我这是在做示范",
            "再忙也要记得吃饭，不然我会担心的",
            "我没有偷懒，我在后台待机",
            "你忙你的，我就在这儿陪你",
            "手机快没电了，记得充电，也记得休息",
            "探索未知！",
    };
    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable menuAutoHide = new Runnable() {
        @Override public void run() { hideMenu(); }
    };

    private int screenW, screenH;
    /** 趴边时累计"往外拉了多少"。位置会一直贴回边缘，但拉出量必须留着， */
    /** 否则一松手就归零，她永远脱离不了边缘（上一版就栽在这）。 */
    private int perchPull = 0;

    /**
     * 趴屏幕下边时露出多少。
     * 1.0 = 整个脑袋都露出来，站在屏幕下沿上（不切）。
     * 调小会让更多部分藏到屏幕外 —— 但要注意趴下边用的裁剪区比脸长，
     * 调小了会先切到脸。
     */
    private static final float BOTTOM_VISIBLE = 1.0f;

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (view == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) { hideBubble(); view.onScreenOff(); }
            else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) view.onScreenOn();
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        measureScreen();

        // 屏幕旋转之后 screenW/screenH 会变，但 Service 不会自动收到通知 ——
        // 不重新量的话，横屏时"扒右边"会算到屏幕中间去。
        registerComponentCallbacks(new ComponentCallbacks() {
            @Override public void onConfigurationChanged(Configuration cfg) {
                measureScreen();
                if (view == null || lp == null) return;
                if (view.getState() == PetView.STATE_PERCH) {
                    // 她正扒在边上：按新的屏幕尺寸重新贴边
                    int e1 = prefs.getInt(KEY_EDGE, 0);
                    if (e1 == PetView.EDGE_BOTTOM) enterPerchBottom(false);
                    else enterPerch(e1 == 1, false);
                } else {
                    applyLayout();   // 悬空态：clamp 会把她收进新屏幕里
                }
            }
            @Override public void onLowMemory() { }
        });
        createChannel();

        soundOn = prefs.getBoolean(KEY_SOUND, true);
        createSoundPool();
        loadDuckSounds();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenRx, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // intent == null 说明是系统把服务"粘性重启"了（START_STICKY）。
        // 以前这里默认按 ACTION_SHOW 走 —— 于是用户从最近任务里划掉 App 之后，
        // 过一阵系统把服务拉起来，她又自己冒出来了（网友反馈过"开游戏加载时她会出现"）。
        if (intent == null && !prefs.getBoolean(KEY_RUNNING, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        startForeground(NOTI_ID, buildNotification());

        if (ACTION_HIDE.equals(action)) {
            prefs.edit().putBoolean(KEY_RUNNING, false).apply();
            removePet();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH.equals(action)) {
            applySizes();
            return START_STICKY;
        }
        if (ACTION_SOUND_DIAG.equals(action)) {
            runSoundDiagnostic();
            return START_STICKY;
        }
        if (ACTION_NEXT_SKIN.equals(action)) {
            int n = (prefs.getInt(KEY_SKIN, 0) + 1) % PetView.SKINS.length;
            prefs.edit().putInt(KEY_SKIN, n).apply();
            if (view != null) view.applySkin(n);
            else if (prefs.getBoolean(KEY_RUNNING, false)) addPet();
            return START_STICKY;
        }
        if (ACTION_NEXT_FACE.equals(action)) {
            if (view != null) {
                view.cycleFace();
                view.pokeFeedback();
            } else {
                // 设置页打开时她不在，先记住，回来再补
                pendingFace = (pendingFace + 1) % 7;
            }
            return START_STICKY;
        }
        if (ACTION_PREVIEW_DUCK.equals(action)) {
            duckSel = prefs.getInt(KEY_DUCK, 0);
        if (bubbleView != null && bubbleStyle != prefs.getInt(KEY_BUBBLE_STYLE, 0)) {
            hideBubble();
            bubbleView = null;
            bubbleStyle = -1;
        }
            if (soundPool != null) {
                int i = Math.max(0, Math.min(duckIds.length - 1, duckSel));
                if (duckIds[i] != 0) soundPool.play(duckIds[i], 1f, 1f, 1, 0, 1f);
            }
            return START_STICKY;
        }
        if (ACTION_SET_HIDDEN.equals(action)) {
            // 我们自己的设置页在前台时，必须把她收起来 ——
            // 悬浮窗永远在最上层，不收起来就会盖住自己的界面。
            boolean hide = intent.getBooleanExtra(EXTRA_HIDDEN, false);
            hiddenForSettings = hide;
            if (hide) {
                removePet();
            } else if (prefs.getBoolean(KEY_RUNNING, false)) {
                addPet();
            }
            return START_STICKY;
        }
        if (ACTION_FACE_COUNT.equals(action)) {
            int n = view == null ? -1 : view.faceCount();
            sendBroadcast(new Intent(ACTION_FACE_COUNT).putExtra(EXTRA_COUNT, n)
                    .setPackage(getPackageName()));
            return START_STICKY;
        }

        prefs.edit().putBoolean(KEY_RUNNING, true).apply();
        if (!addPet()) {
            prefs.edit().putBoolean(KEY_RUNNING, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (prefs.getBoolean(KEY_PERCHED, false)) {
            enterPerch(prefs.getInt(KEY_EDGE, 0) == 1, false);
        }
        return START_STICKY;
    }

    /**
     * 用户从「最近任务」里划掉了 App —— 这是明确的"我不要它跑"。
     * 真的收起来，别让系统过一会儿把服务拉起来、她又冒出来。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        prefs.edit().putBoolean(KEY_RUNNING, false).apply();
        removePet();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideMenu();
        removePet();
        wanderHandler.removeCallbacks(wanderTick);
        if (soundPool != null) { soundPool.release(); soundPool = null; }
        try { unregisterReceiver(screenRx); } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ---- 悬浮窗 ----

    private boolean addPet() {
        if (view != null) return true;
        if (!canDrawOverlays()) return false;

        view = new PetView(this);
        view.setListener(this);
        view.setHoverHeightDp(prefs.getFloat(KEY_HOVER_H, DEFAULT_HOVER_H));
        view.setPerchWidthDp(prefs.getFloat(KEY_PERCH_W, DEFAULT_PERCH_W));
        view.setAmpScale(prefs.getFloat(KEY_AMP, DEFAULT_AMP));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                Math.round(view.windowW()), Math.round(view.windowH()), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (screenW - lp.width) / 2;
        lp.y = Math.max(0, prefs.getInt(KEY_Y, (int) (screenH * 0.42f)));

        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            Toast.makeText(this, "悬浮窗添加失败，请确认已授予「显示在其他应用上层」权限",
                    Toast.LENGTH_LONG).show();
            view = null;
            return false;
        }
        // 恢复上次的样子。不恢复的话，从设置页回来她会变成一个
        // "站在屏幕正中间、默认皮肤、默认表情"的新人 —— 趴边状态也会丢。
        int sk = prefs.getInt(KEY_SKIN, 0);
        if (sk > 0 && sk < PetView.SKINS.length) view.applySkin(sk);
        if (pendingFace != 0) {
            view.setFace(pendingFace);
            pendingFace = 0;
        }
        if (prefs.getBoolean(KEY_PERCHED, false)) {
            int e0 = prefs.getInt(KEY_EDGE, 0);
            if (e0 == PetView.EDGE_BOTTOM) enterPerchBottom(false);
            else enterPerch(e0 == 1, false);
        } else {
            lp.x = prefs.getInt(KEY_X, lp.x);
            applyLayout();
        }
        // 服务被系统重启后字段会回到默认值，先把开关读回来再挂定时器
        soundOn = prefs.getBoolean(KEY_SOUND, true);
        wanderOn = prefs.getBoolean(KEY_WANDER, true);
        restartWander();
        return true;
    }

    private void removePet() {
        hideBubble();
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        view = null;
        // 不重置的话，她回来之后自动溜达的定时器永远不会重新挂上
        wanderStarted = false;
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.provider.Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void applySizes() {
        // 这两个开关跟"她在不在窗口里"无关，必须先更新。
        // 设置页打开时她已经从窗口移除（view == null），以前的写法会在这里提前
        // return，于是拨动音效开关后 soundOn 一直是旧值 —— 开关看着像失灵。
        boolean prevWander = wanderOn;
        soundOn = prefs.getBoolean(KEY_SOUND, true);
        wanderOn = prefs.getBoolean(KEY_WANDER, true);
        duckSel = prefs.getInt(KEY_DUCK, 0);
        if (bubbleView != null && bubbleStyle != prefs.getInt(KEY_BUBBLE_STYLE, 0)) {
            hideBubble();
            bubbleView = null;
            bubbleStyle = -1;
        }
        // 开关动过就重挂一次定时器 —— 不重挂的话，那个 tick 一旦退出就再也回不来，
        // 表现为"关了还爬"或者"开了不爬"。
        if (wanderOn != prevWander || !wanderOn) restartWander();
        if (view == null || lp == null) return;
        view.setHoverHeightDp(prefs.getFloat(KEY_HOVER_H, DEFAULT_HOVER_H));
        view.setPerchWidthDp(prefs.getFloat(KEY_PERCH_W, DEFAULT_PERCH_W));
        view.setAmpScale(prefs.getFloat(KEY_AMP, DEFAULT_AMP));
        int sk = prefs.getInt(KEY_SKIN, 0);
        if (sk != view.currentSkin()) view.applySkin(sk);
        applyLayout();
    }

    private void applyLayout() {
        if (view == null || lp == null) return;
        if (wanderOn && !wanderStarted) {     // 窗口一就位就把溜达的定时器挂上（关掉就别挂）
            wanderStarted = true;
            wanderHandler.postDelayed(wanderTick, 4000);
        }
        if (view.getState() == PetView.STATE_HOVER) {
            lp.width = Math.round(view.windowW());
            lp.height = Math.round(view.windowH());
        } else {
            lp.width = Math.round(view.perchWindowW());
            lp.height = Math.round(view.perchWindowH());
        }
        clamp();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        if (bubbleAdded && bubbleView != null && bubbleLp != null) {
            positionBubble();
            try { wm.updateViewLayout(bubbleView, bubbleLp); } catch (Exception ignored) { }
        }
    }

    /**
     * 量真实的显示区域。
     *
     * 不能用 getResources().getDisplayMetrics().heightPixels —— 它不含导航栏，
     * 于是她最多只能停在导航栏上方，怎么拖都到不了屏幕最底部（用户反馈过）。
     */
    private void measureScreen() {
        try {
            WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Rect b = w.getCurrentWindowMetrics().getBounds();
                screenW = b.width();
                screenH = b.height();
            } else {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                w.getDefaultDisplay().getRealMetrics(dm);
                screenW = dm.widthPixels;
                screenH = dm.heightPixels;
            }
        } catch (Exception e) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
    }

    private void clamp() {
        int w = lp.width, h = lp.height;
        if (lp.x < 0) lp.x = 0;
        if (lp.x > screenW - w) lp.x = Math.max(0, screenW - w);
        // 趴屏幕下边时她本来就该有一部分在屏幕外，不能按普通规则夹 y
        if (view != null && view.getState() == PetView.STATE_PERCH
                && view.getPerchEdge() == PetView.EDGE_BOTTOM) {
            // 用脑袋真实高度定位，不是窗口高度 —— 窗口比脑袋高，
            // 拿窗口高度定位会让她离下沿差出一截。
            lp.y = Math.round(screenH - view.perchVisibleH() * BOTTOM_VISIBLE);
            return;
        }
        int topLimit = -Math.round(h * 0.12f);
        int bottomLimit = screenH - Math.round(h * 0.88f);
        if (lp.y < topLimit) lp.y = topLimit;
        if (lp.y > bottomLimit) lp.y = bottomLimit;
    }

    // ---- 长按菜单 ----

    // ---- 说话气泡 ----

    private void showBubble() {
        if (view == null || wm == null || lp == null) return;
        int style = prefs.getInt(KEY_BUBBLE_STYLE, 0);
        if (bubbleView == null || bubbleStyle != style) {
            hideBubble();
            bubbleView = null;
            bubbleStyle = style;
            bubbleView = new TextView(this);
            bubbleView.setTextColor(style == 1 ? 0xFFF2F5FA : 0xFF262A33);
            bubbleView.setTextSize(14f);
            bubbleView.setLineSpacing(dp(3), 1f);
            bubbleView.setGravity(Gravity.CENTER_VERTICAL);
            int tail = Math.round(dp(10));
            bubbleView.setMaxWidth(Math.round(dp(260)));
            bubbleView.setPadding(Math.round(dp(16)), Math.round(dp(10)),
                    Math.round(dp(16)), Math.round(dp(10)) + tail);
            bubbleView.setBackground(new BubbleBg(style == 1 ? 0xF01B2033 : 0xFFFFFFFF, tail));
            bubbleView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { hideBubble(); }
            });
        }
        bubbleView.setText(nextLine());
        bubbleView.measure(View.MeasureSpec.makeMeasureSpec(Math.round(dp(280)), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (bubbleLp == null) {
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            bubbleLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            bubbleLp.gravity = Gravity.TOP | Gravity.START;
        }
        positionBubble();
        try {
            if (!bubbleAdded) { wm.addView(bubbleView, bubbleLp); bubbleAdded = true; }
            else wm.updateViewLayout(bubbleView, bubbleLp);
        } catch (Exception e) {
            bubbleAdded = false;
            return;
        }
        handler.removeCallbacks(bubbleAutoHide);
        handler.postDelayed(bubbleAutoHide, 3600);
    }

    private void hideBubble() {
        handler.removeCallbacks(bubbleAutoHide);
        if (bubbleView != null && bubbleAdded && wm != null) {
            try { wm.removeView(bubbleView); } catch (Exception ignored) { }
        }
        bubbleAdded = false;
    }

    private void positionBubble() {
        if (bubbleView == null || bubbleLp == null || lp == null) return;
        int bw = bubbleView.getMeasuredWidth();
        int bh = bubbleView.getMeasuredHeight();
        if (bw <= 0) bw = Math.round(dp(180));
        if (bh <= 0) bh = Math.round(dp(56));
        int x = lp.x + (lp.width - bw) / 2;
        int y = lp.y - bh - Math.round(dp(4));
        if (y < Math.round(dp(6))) y = lp.y + lp.height + Math.round(dp(4));
        x = Math.max(Math.round(dp(4)), Math.min(x, screenW - bw - Math.round(dp(4))));
        bubbleLp.x = x;
        bubbleLp.y = y;
    }

    private String nextLine() {
        String[] lines = currentLines();
        if (lines.length == 0) return "";
        int i = rnd.nextInt(lines.length);
        if (i == lastLine) i = (i + 1) % lines.length;
        lastLine = i;
        return lines[i];
    }

    /** 用户在设置页自己加的台词；没设过就用默认的 18 句 */
    private String[] currentLines() {
        String raw = prefs.getString(KEY_LINES, "");
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_LINES;
        String[] parts = raw.split("\n");
        ArrayList<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? DEFAULT_LINES : out.toArray(new String[0]);
    }

    private static class BubbleBg extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int tail;
        BubbleBg(int color, int tail) { paint.setColor(color); this.tail = tail; }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) * 0.16f;
            RectF body = new RectF(b.left, b.top, b.right, b.bottom - tail);
            canvas.drawRoundRect(body, r, r, paint);
            float cx = b.centerX() + b.width() * 0.10f;
            Path path = new Path();
            path.moveTo(cx - tail * 0.9f, body.bottom - 2);
            path.lineTo(cx + tail * 0.9f, body.bottom - 2);
            path.lineTo(cx, b.bottom);
            path.close();
            canvas.drawPath(path, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    public void onLongPress() {
        hideBubble();
        if (menuView != null) { hideMenu(); return; }
        if (view == null) return;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01B2033);
        bg.setCornerRadius(dp(16));
        bg.setStroke(Math.round(dp(1)), 0x55FFFFFF);
        row.setBackground(bg);
        int pad = Math.round(dp(5));
        row.setPadding(pad, pad, pad, pad);
        row.setElevation(dp(6));

        row.addView(menuButton("皮肤：" + PetView.SKINS[prefs.getInt(KEY_SKIN, 0) % PetView.SKINS.length].name,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = (prefs.getInt(KEY_SKIN, 0) + 1) % PetView.SKINS.length;
                prefs.edit().putInt(KEY_SKIN, n).apply();
                ((TextView) v).setText("皮肤：" + PetView.SKINS[n].name);
                applySizes();          // 两套皮肤的宽高比不同，换完要重新量窗口
                // 换一次就重新计时，方便连着点着挑
                handler.removeCallbacks(menuAutoHide);
                handler.postDelayed(menuAutoHide, MENU_AUTO_HIDE_MS);
            }
        }));
        row.addView(menuButton("设置", new View.OnClickListener() {
            @Override public void onClick(View v) { hideMenu(); openSettings(); }
        }));
        row.addView(menuButton("关闭", new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideMenu();
                prefs.edit().putBoolean(KEY_RUNNING, false).apply();
                removePet();
                stopSelf();
            }
        }));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        menuLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        // 点菜单外面的地方也把菜单收掉（原来只能等 6 秒自动消失）
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        menuLp.gravity = Gravity.TOP | Gravity.START;
        menuLp.x = 0;
        menuLp.y = 0;
        // 收到 ACTION_OUTSIDE = 用户点了菜单外面 → 收起
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    hideMenu();
                    return true;
                }
                return false;
            }
        });
        menuView = row;

        try {
            wm.addView(menuView, menuLp);
        } catch (Exception e) {
            menuView = null;
            return;
        }
        menuView.post(new Runnable() {
            @Override public void run() { placeMenu(); }
        });
        handler.postDelayed(menuAutoHide, MENU_AUTO_HIDE_MS);
    }

    private TextView menuButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(15);
        tv.setPadding(Math.round(dp(18)), Math.round(dp(10)),
                Math.round(dp(18)), Math.round(dp(10)));
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    private void placeMenu() {
        if (menuView == null || view == null || lp == null) return;
        int mw = menuView.getWidth(), mh = menuView.getHeight();
        int gap = Math.round(dp(8));
        int x = lp.x + lp.width + gap;
        if (x + mw > screenW) x = lp.x - mw - gap;
        if (x < 0) x = Math.max(0, (screenW - mw) / 2);
        int y = lp.y + (lp.height - mh) / 2;
        if (y < 0) y = 0;
        if (y + mh > screenH) y = screenH - mh;
        menuLp.x = x;
        menuLp.y = y;
        try { wm.updateViewLayout(menuView, menuLp); } catch (Exception ignored) { }
    }

    private void hideMenu() {
        handler.removeCallbacks(menuAutoHide);
        if (menuView != null && wm != null) {
            try { wm.removeView(menuView); } catch (Exception ignored) { }
        }
        menuView = null;
    }

    private void openSettings() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    // ---- 状态切换 ----

    /** 趴到屏幕下边：脑袋从下沿探出来，水平位置不动 */
    private void enterPerchBottom(boolean persist) {
        if (view == null) return;
        measureScreen();
        perchPull = 0;
        view.stopWanderSeq();
        view.setState(PetView.STATE_PERCH);
        view.setPerchEdge(PetView.EDGE_BOTTOM);
        view.setMirror(false);
        lp.width = Math.round(view.perchWindowW());
        lp.height = Math.round(view.perchWindowH());
        clamp();   // clamp 里会把 y 钉在屏幕下沿
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        if (persist) {
            prefs.edit().putBoolean(KEY_PERCHED, true).putInt(KEY_EDGE, PetView.EDGE_BOTTOM).apply();
        }
    }

    private void enterPerch(boolean right, boolean persist) {
        if (view == null) return;
        measureScreen();   // 兜底：万一旋转监听没触发，这里也要用最新的屏幕尺寸
        perchPull = 0;
        view.stopWanderSeq();
        view.setState(PetView.STATE_PERCH);
        view.setPerchEdge(right ? PetView.EDGE_RIGHT : PetView.EDGE_LEFT);
        view.setMirror(right);
        lp.width = Math.round(view.perchWindowW());
        lp.height = Math.round(view.perchWindowH());
        lp.x = right ? screenW - lp.width : 0;
        clamp();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        if (bubbleAdded && bubbleView != null && bubbleLp != null) {
            positionBubble();
            try { wm.updateViewLayout(bubbleView, bubbleLp); } catch (Exception ignored) { }
        }
        if (persist) {
            prefs.edit().putBoolean(KEY_PERCHED, true).putInt(KEY_EDGE, right ? 1 : 0).apply();
        }
    }

    private void exitPerch() {
        if (view == null) return;
        view.setState(PetView.STATE_HOVER);
        view.setMirror(false);
        applyLayout();
        prefs.edit().putBoolean(KEY_PERCHED, false).apply();
    }

    // ---- PetView.Listener ----

    @Override
    public void onDragStart() { hideMenu(); hideBubble(); perchPull = 0; if (view != null) view.stopWanderSeq(); }

    @Override
    public void onDrag(float dx, float dy) {
        if (view == null) return;
        lp.y += Math.round(dy);

        if (view.getState() == PetView.STATE_PERCH
                && view.getPerchEdge() == PetView.EDGE_BOTTOM) {
            // 趴在下边：往上拉才把她拉起来（左右拉不算）
            perchPull += Math.round(-dy);
            if (perchPull < 0) perchPull = 0;
            if (perchPull > screenH * 0.08f) {
                perchPull = 0;
                exitPerch();
                return;
            }
            clamp();   // 还趴着：钉回屏幕下沿
            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
            return;
        }

        if (view.getState() == PetView.STATE_PERCH) {
            boolean right = prefs.getInt(KEY_EDGE, 0) == 1;
            // "往外拉"的方向：贴右边时往左拉才算数，贴左边时往右拉才算数
            perchPull += Math.round(right ? -dx : dx);
            if (perchPull < 0) perchPull = 0;             // 往里推不算
            if (perchPull > screenW * 0.12f) {            // 拉够远了 → 脱离边缘
                perchPull = 0;
                lp.x = right ? screenW - lp.width : 0;
                exitPerch();
                return;
            }
            // 没拉够：位置贴回边缘，但 perchPull 留着（关键，别再抹掉）
            lp.x = right ? screenW - lp.width : 0;
        } else {
            lp.x += Math.round(dx);
        }

        clamp();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        if (bubbleAdded && bubbleView != null && bubbleLp != null) {
            positionBubble();
            try { wm.updateViewLayout(bubbleView, bubbleLp); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onDragEnd(float rawX, float rawY) {
        if (view == null) return;
        prefs.edit().putInt(KEY_Y, lp.y).putInt(KEY_X, lp.x).apply();
        if (view.getState() == PetView.STATE_PERCH) {
            // 兜底：松手时还停在趴边态，就确保她贴回边上（上下位置保留）
            perchPull = 0;
            lp.x = (prefs.getInt(KEY_EDGE, 0) == 1) ? screenW - lp.width : 0;
            clamp();
            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
            return;
        }
        perchPull = 0;

        // 吸附判定不能用一个固定像素值：她调小之后窗口本身就窄，
        // 固定的 56dp 会占掉她宽度的一大半，导致"稍微靠近边缘"就被吸走。
        // 所以取"固定值和窗口宽度 25% 里更小的那个"。
        float snapDp = SNAP_DP * getResources().getDisplayMetrics().density;
        float snap = Math.min(snapDp, lp.width * 0.25f);
        if (lp.x <= snap) enterPerch(false, true);
        else if (lp.x + lp.width >= screenW - snap) enterPerch(true, true);
        // 拖到屏幕下沿附近 → 趴到下边去（放在左右之后，角落处优先左右）
        else if (lp.y + lp.height >= screenH - Math.min(snapDp, lp.height * 0.30f)) enterPerchBottom(true);
        else applyLayout();
    }

    /**
     * 单击换表情在 PetView 里做；这里负责音效和缺素材时的兜底提示。
     */
    @Override
    public void onWanderSeqEnd() {
        lp.x = seqToX;
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        prefs.edit().putInt(KEY_X, lp.x).apply();
        wanderTargetX = -1f;
        wanderVel = 0f;
        wanderWaitUntil = System.currentTimeMillis() + 1500 + rnd.nextInt(2500);
        wanderHandler.removeCallbacks(wanderTick);
        wanderHandler.postDelayed(wanderTick, 400);
    }

    @Override
    public void onTap() {
        showBubble();
        if (view != null && view.faceCount() == 0) {
            Toast.makeText(this, "表情素材没加载成功", Toast.LENGTH_SHORT).show();
        }
        if (!soundOn) return;
        playDuckAndWarn();
    }

    // ---- 音效 ----

    /**
     * 音效池。USAGE_MEDIA → STREAM_MUSIC（媒体音量）。
     *
     * 这里以前是 USAGE_ASSISTANCE_SONIFICATION，它落在 STREAM_SYSTEM。
     * MIUI / HyperOS 上"系统音效"那条通道在静音模式、或用户关掉触摸提示音后是 0，
     * 于是 play() 返回成功却完全没声音 —— 正是"点她有反应、就是不出声"。
     */
    private void createSoundPool() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            for (int i = 0; i < duckIds.length; i++) {
                if (duckIds[i] == sampleId) duckLoadStatus[i] = status;
            }
            android.util.Log.d("WhalePetAudio", "loadComplete sample=" + sampleId
                    + " status=" + status);
        });
    }

    /** load() 是异步的，这里返回 0 说明这一步就失败了 —— 下次点击会再试一次 */
    private void loadDuckSounds() {
        if (soundPool == null) return;
        duckIds[0] = soundPool.load(this, R.raw.duck1, 1);
        duckIds[1] = soundPool.load(this, R.raw.duck2, 1);
        duckIds[2] = soundPool.load(this, R.raw.duck3, 1);
        duckIds[3] = soundPool.load(this, R.raw.duck4, 1);
        duckIds[4] = soundPool.load(this, R.raw.duck5, 1);
        duckIds[5] = soundPool.load(this, R.raw.duck6, 1);
        duckIds[6] = soundPool.load(this, R.raw.duck7, 1);
        duckIds[7] = soundPool.load(this, R.raw.duck8, 1);
        android.util.Log.d("WhalePetAudio", "load ids="
                + duckIds[0] + "," + duckIds[1] + "," + duckIds[2]);
    }

    /**
     * 随机播一声鸭子叫，返回 SoundPool.play() 的 streamId（0 = 没播出去）。
     * 采样还没 load 完时补一次 load —— 否则刚启动那几下点击是哑的。
     */
    private int playDuckSound() {
        if (soundPool == null) return 0;
        // 放用户选中的那一个（原来是从 8 个里随机挑 —— 用户没得选）
        int sel = Math.max(0, Math.min(duckIds.length - 1, duckSel));
        int id = duckIds[sel];
        if (id == 0) {
            loadDuckSounds();
            return 0;
        }
        int streamId = soundPool.play(id, 1f, 1f, 1, 0, 1f);
        android.util.Log.d("WhalePetAudio", "tap sample=" + id + " streamId=" + streamId
                + " soundOn=" + soundOn + " " + volumeReport());
        if (streamId == 0) loadDuckSounds();
        return streamId;
    }

    private void playDuckAndWarn() {
        int streamId = playDuckSound();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        int vol = am.getStreamVolume(SOUND_STREAM);
        boolean muted = false;
        try { muted = am.isStreamMute(SOUND_STREAM); } catch (Exception ignored) { }
        // 只有确实查出问题才提示；正常出声时保持安静
        if (streamId != 0 && !muted && vol > 0) return;
        hintSilent(streamId, vol, muted);
    }

    /**
     * 没响的时候把原因直接说清楚，别让用户对着一个哑巴开关发呆。
     * 一分钟最多提示一次，免得连点时刷屏。
     */
    private void hintSilent(int streamId, int vol, boolean muted) {
        long now = System.currentTimeMillis();
        if (now - lastSilentHintMs < 60_000L) return;
        lastSilentHintMs = now;

        String why;
        if (streamId == 0) {
            why = "音效还没加载好，再点一下试试";
        } else if (muted) {
            why = "媒体音量被静音了 —— 关掉静音模式，或关掉 MIUI「静音时同时静音媒体」";
        } else if (vol <= 0) {
            why = "媒体音量是 0，按一下音量键调大就有声了";
        } else {
            why = "声音已经送出去了，检查是不是连着蓝牙耳机";
        }
        Toast.makeText(this, "鸭子叫没响：" + why, Toast.LENGTH_LONG).show();
    }

    private String volumeReport() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return "audio=null";
        StringBuilder sb = new StringBuilder("stream=").append(SOUND_STREAM)
                .append(" vol=").append(am.getStreamVolume(SOUND_STREAM))
                .append('/').append(am.getStreamMaxVolume(SOUND_STREAM));
        try {
            sb.append(" muted=").append(am.isStreamMute(SOUND_STREAM));
        } catch (Exception ignored) { }
        sb.append(" ringer=").append(am.getRingerMode());
        return sb.toString();
    }

    /**
     * 音效诊断：先真的播一声（数字看不出问题，听得到才算数），
     * 再把音频通道 / 音量 / 静音状态 / 本次播放结果一起报出来。
     */
    private void runSoundDiagnostic() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int streamId = playDuckSound();

        int vol = am == null ? -1 : am.getStreamVolume(SOUND_STREAM);
        int volMax = am == null ? -1 : am.getStreamMaxVolume(SOUND_STREAM);
        boolean muted = false;
        try { muted = am != null && am.isStreamMute(SOUND_STREAM); } catch (Exception ignored) { }
        int ringer = am == null ? -1 : am.getRingerMode();
        String ringerText = ringer == AudioManager.RINGER_MODE_SILENT ? "静音"
                : ringer == AudioManager.RINGER_MODE_VIBRATE ? "震动" : "正常";

        String msg = "音效诊断\n"
                + "sound_on=" + soundOn
                + "　加载=" + duckLoadStatus[0] + "," + duckLoadStatus[1] + "," + duckLoadStatus[2] + "\n"
                + "音频通道=媒体音量(STREAM_MUSIC)\n"
                + "媒体音量=" + vol + "/" + volMax + (muted ? "（已被系统静音）" : "") + "\n"
                + "铃声模式=" + ringerText + "\n"
                + "本次播放=" + (streamId == 0 ? "失败" : "成功 streamId=" + streamId) + "\n"
                + diagAdvice(streamId, vol, muted);
        android.util.Log.d("WhalePetAudio", msg.replace('\n', ' '));
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private String diagAdvice(int streamId, int vol, boolean muted) {
        if (streamId == 0) return "→ 音效没加载完或加载失败，再点一次";
        if (muted) return "→ 媒体通道被静音：关静音模式 / 关 MIUI「静音时同时静音媒体」";
        if (vol <= 0) return "→ 媒体音量是 0：按音量键调大";
        return "→ 通道与音量都正常。若仍听不到，看是否连着蓝牙耳机";
    }

    // ---- 通知 ----

    // ---------------- 自动溜达 ----------------
    // 她的位置是"窗口位置"，只有 Service 能动。所以这里挪窗口，
    // 同时告诉 View "正在走"（由 View 加摇晃和颠簸）。
    private boolean wanderOn = true;
    private final Handler wanderHandler = new Handler(Looper.getMainLooper());
    private float wanderX = 0f, wanderTargetX = -1f, wanderVel = 0f;
    private int seqFromX, seqToX;
    private long wanderWaitUntil = 0L;
    private boolean wanderStarted = false;

    private final Runnable wanderTick = new Runnable() {
        @Override public void run() {
            if (!wanderOn || view == null || lp == null) {
                if (view != null) view.setWalking(false);
                wanderStarted = false;
                wanderTargetX = -1f;
                return;
            }
            if (!prefs.getBoolean(KEY_RUNNING, false)) return;
            if (view.isWanderSeqPlaying()) {
                view.setWalking(false);
                int f = view.getSeqFrame();
                if (f >= 30 && f <= 74) {
                    float p = (f - 30) / 44f;
                    p = p * p * (3f - 2f * p);           // 平滑加减速
                    lp.x = Math.round(seqFromX + (seqToX - seqFromX) * p);
                    try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
                } else if (f > 74) {
                    lp.x = seqToX;
                    try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
                }
                wanderHandler.postDelayed(this, 16);
                return;
            }
            // 趴边 / 正在被拖 / 刚被碰过 —— 都不动
            if (view.getState() != PetView.STATE_HOVER) {
                // 趴边时不溜达；等回到悬空态由 applyLayout 重新挂
                view.setWalking(false);
                wanderStarted = false;
                wanderTargetX = -1f;
                return;
            }
            if (view.isDragging() || view.msSinceTouch() < 5000) {
                view.setWalking(false);
                wanderHandler.postDelayed(this, 300);
                return;
            }
            long now = System.currentTimeMillis();
            if (now < wanderWaitUntil) {
                view.setWalking(false);
                wanderHandler.postDelayed(this, 150);
                return;
            }
            if (wanderTargetX < 0f) {                 // 挑一个新目标
                if (view.hasWanderSeq()) {            // 有整段动作：转身 → 走过去 → 转回来
                    view.setWalking(false);
                    int margin = Math.round(dp(14));
                    int lo = margin;
                    int hi = Math.max(margin + 1, screenW - lp.width - margin);
                    int dist = Math.round(dp(70)) + rnd.nextInt(Math.max(1, Math.round(dp(150))));
                    int dir = rnd.nextBoolean() ? 1 : -1;
                    int cand = lp.x + dir * dist;
                    if (cand < lo || cand > hi) cand = lp.x - dir * dist;
                    seqFromX = lp.x;
                    seqToX = Math.max(lo, Math.min(hi, cand));
                    if (seqToX == seqFromX) seqToX = Math.min(hi, seqFromX + Math.round(dp(80)));
                    view.setSeqMirror(seqToX > seqFromX);
                    view.playWanderSeq();
                    wanderHandler.postDelayed(this, 16);
                    return;
                }
                wanderX = lp.x;
                int margin = Math.round(dp(14));
                int hi = Math.max(margin + 1, screenW - lp.width - margin);
                // 目标随机远近：多数是小碎步，偶尔走远一点
                int span = hi - margin;
                int step = rnd.nextInt(100) < 65
                        ? Math.round(dp(20)) + rnd.nextInt(Math.max(1, Math.min(span, Math.round(dp(90)))))
                        : span / 2 + rnd.nextInt(Math.max(1, span / 2));
                int dir = rnd.nextBoolean() ? 1 : -1;
                int cand = Math.round(wanderX) + dir * step;
                if (cand < margin || cand > hi) cand = Math.round(wanderX) - dir * step;
                wanderTargetX = Math.max(margin, Math.min(hi, cand));
                wanderWaitUntil = now + 800;
                wanderHandler.postDelayed(this, 150);
                return;
            }
            float d = wanderTargetX - wanderX;
            float dist = Math.abs(d);
            if (dist < 2.5f && wanderVel < 0.35f) {   // 到站，歇一会儿
                wanderX = wanderTargetX;
                wanderTargetX = -1f;
                wanderVel = 0f;
                view.setWalkMotion(0f, 0f);
                view.setWalking(false);
                wanderWaitUntil = now + 1400 + rnd.nextInt(2600);
                wanderHandler.postDelayed(this, 120);
                return;
            }
            // 偶尔停下来看看四周，别一路匀速滑过去
            if (rnd.nextInt(1000) < 10) {
                wanderVel = 0f;
                view.setWalkMotion(0f, 0f);
                view.setWalking(false);
                wanderWaitUntil = now + 260 + rnd.nextInt(340);
                wanderHandler.postDelayed(this, 150);
                return;
            }
            // 速度做加减速：离目标越近目标速度越低，再平滑逼近
            float targetSpeed = Math.min(4.6f, dist * 0.16f);
            wanderVel += (targetSpeed - wanderVel) * 0.16f;
            if (wanderVel < 0.05f) wanderVel = 0f;
            if (wanderVel > 0f) {
                wanderX += Math.signum(d) * Math.min(wanderVel, dist);
                lp.x = Math.round(wanderX);
                try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
                view.setWalking(true);
                view.setWalkMotion(wanderVel, Math.signum(d));
            } else {
                view.setWalkMotion(0f, 0f);
                view.setWalking(false);
            }
            wanderHandler.postDelayed(this, 16);
        }
    };

    /**
     * 按当前开关重新挂一次溜达定时器。
     *
     * wanderTick 退出时不会再排下一次（这是有意的：关掉就该停），
     * 但那样一来它就无法自己恢复 —— 必须由外部重新挂上。
     */
    private void restartWander() {
        wanderHandler.removeCallbacks(wanderTick);
        wanderStarted = false;
        if (!wanderOn) {
            if (view != null) view.setWalking(false);   // 立刻停下，别等下一次 tick
            return;
        }
        if (view != null && lp != null) {
            wanderStarted = true;
            wanderHandler.postDelayed(wanderTick, 4000);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "鲸宠",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent hide = new Intent(this, PetService.class).setAction(ACTION_HIDE);
        PendingIntent piHide = PendingIntent.getService(this, 1, hide, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("白饭鱼")
                .setContentText("正在你桌面上吃白饭 · 长按她可以调整或关闭")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "收回", piHide).build());
        return b.build();
    }
}
