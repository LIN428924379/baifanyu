package com.whalepet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.io.IOException;
import java.io.InputStream;

/**
 * 宠物的绘制、动作、皮肤与交互。
 *
 * 皮肤 = 一套放在 assets/skins/&lt;名字&gt;/ 里的立绘：
 *   一张基准正面立绘 pet_stand.png + 若干表情 pet_face_*.png
 * 加一套皮肤只要在 SKINS 里加一行 + 往 assets/skins/ 放图。
 *
 * 表情是"整张换图"，不是贴一块脸上去 ——
 * 表情图和底图是两次独立生成的，五官位置和缩放都有偏差，
 * 任何"贴片"方案都会在「露接缝」和「露重影」之间二选一。
 * 整张换掉就没这个问题：每张图内部是自洽的，身体也不会跳。
 */
public class PetView extends View implements Choreographer.FrameCallback {

    public static final int STATE_HOVER = 0;
    public static final int STATE_PERCH = 1;

    /** 扒在哪条边上 */
    public static final int EDGE_LEFT = 0, EDGE_RIGHT = 1, EDGE_BOTTOM = 2;

    private static final String DIR_CLAUDE = "skins/claude/";
    private static final String DIR_MAID = "skins/maid/";
    private static final String DIR_WINTER_WHITE = "skins/winter_white/";
    private static final String DIR_WINTER_BLUE = "skins/winter_blue/";
    private static final String DIR_BERET = "skins/beret/";
    private static final String DIR_BASIN = "skins/basin/";

    // 头部区块的默认比例（女仆装那套调的）
    private static final float DEF_HX0 = 35f / 1191f;
    private static final float DEF_HX1 = 985f / 1191f;
    private static final float DEF_HY0 = 30f / 1514f;
    private static final float DEF_HY1 = 800f / 1514f;

    /**
     * 一套皮肤。
     *
     * hx0/hx1/hy0/hy1 = 趴边时显示素材的哪一块（占素材宽高的比例）。
     * 必须每套皮肤单独给 —— 饭盆头那顶盆横向撑得比女仆装宽得多，
     * 沿用同一套比例会把盆的右边切掉一块。
     */
    public static final class Skin {
        public final String name, base;
        public final String[] faces;
        public final float hx0, hx1, hy0, hy1;
        /**
         * 趴左右边时的额外缩放（只作用于侧边，不影响趴下边）。
         * 各套皮肤的头块比例不一样：饭盆头那块带整顶盆，同样宽度下看着更大，
         * 所以给它单独收一点。
         */
        public final float perchSideScale;

        Skin(String name, String base, String[] faces) {
            this(name, base, faces, DEF_HX0, DEF_HX1, DEF_HY0, DEF_HY1, 1f);
        }

        Skin(String name, String base, String[] faces,
             float hx0, float hx1, float hy0, float hy1) {
            this(name, base, faces, hx0, hx1, hy0, hy1, 1f);
        }

        Skin(String name, String base, String[] faces,
             float hx0, float hx1, float hy0, float hy1, float perchSideScale) {
            this.name = name;
            this.base = base;
            this.faces = faces;
            this.hx0 = hx0;
            this.hx1 = hx1;
            this.hy0 = hy0;
            this.hy1 = hy1;
            this.perchSideScale = perchSideScale;
        }
    }

    // 第一套是默认皮肤
    public static final Skin[] SKINS = {
            new Skin("Claude", DIR_CLAUDE + "pet_stand.png", new String[]{
                    DIR_CLAUDE + "pet_face_happy.png",
                    DIR_CLAUDE + "pet_face_sad.png",
                    DIR_CLAUDE + "pet_face_angry.png",
                    DIR_CLAUDE + "pet_face_surprised.png",
                    DIR_CLAUDE + "pet_face_shy.png",
                    DIR_CLAUDE + "pet_face_confused.png",
            }, 0.128f, 0.91f, 0.01f, 0.50f, 0.9f),
            new Skin("女仆装", DIR_MAID + "pet_stand.png", new String[]{
                    DIR_MAID + "pet_face_happy.png",
                    DIR_MAID + "pet_face_sad.png",
                    DIR_MAID + "pet_face_angry.png",
                    DIR_MAID + "pet_face_surprised.png",
                    DIR_MAID + "pet_face_shy.png",
                    DIR_MAID + "pet_face_confused.png",
            }, 0.076f, 0.929f, 0.01f, 0.50f, 0.9f),
            new Skin("白色冬装", DIR_WINTER_WHITE + "pet_stand.png", new String[]{
                    DIR_WINTER_WHITE + "pet_face_happy.png",
                    DIR_WINTER_WHITE + "pet_face_sad.png",
                    DIR_WINTER_WHITE + "pet_face_angry.png",
                    DIR_WINTER_WHITE + "pet_face_surprised.png",
                    DIR_WINTER_WHITE + "pet_face_shy.png",
                    DIR_WINTER_WHITE + "pet_face_confused.png",
            }, 0.107f, 0.923f, 0.01f, 0.50f, 0.9f),
            new Skin("蓝色冬装", DIR_WINTER_BLUE + "pet_stand.png", new String[]{
                    DIR_WINTER_BLUE + "pet_face_happy.png",
                    DIR_WINTER_BLUE + "pet_face_sad.png",
                    DIR_WINTER_BLUE + "pet_face_angry.png",
                    DIR_WINTER_BLUE + "pet_face_surprised.png",
                    DIR_WINTER_BLUE + "pet_face_shy.png",
                    DIR_WINTER_BLUE + "pet_face_confused.png",
            }, 0.139f, 0.872f, 0.01f, 0.50f, 0.9f),
            new Skin("贝雷帽", DIR_BERET + "pet_stand.png", new String[]{
                    DIR_BERET + "pet_face_happy.png",
                    DIR_BERET + "pet_face_sad.png",
                    DIR_BERET + "pet_face_angry.png",
                    DIR_BERET + "pet_face_surprised.png",
                    DIR_BERET + "pet_face_shy.png",
                    DIR_BERET + "pet_face_confused.png",
            }, 0.086f, 0.911f, 0.01f, 0.50f, 0.9f),
            new Skin("饭盆头", DIR_BASIN + "pet_stand.png", new String[]{
                    DIR_BASIN + "pet_face_happy.png",
                    DIR_BASIN + "pet_face_sad.png",
                    DIR_BASIN + "pet_face_angry.png",
                    DIR_BASIN + "pet_face_surprised.png",
                    DIR_BASIN + "pet_face_shy.png",
                    DIR_BASIN + "pet_face_confused.png",
            }, 0.111f, 0.9f, 0.01f, 0.50f, 0.85f),
    };

    private static final float NS = 1_000_000_000f;
    private static final long LONG_PRESS_MS = 800;

    public interface Listener {
        void onDragStart();
        void onDrag(float dx, float dy);
        void onDragEnd(float rawX, float rawY);
        void onTap();
        void onLongPress();
    }

    private Context ctx;
    private Bitmap baseBmp;      // 基准立绘（跟皮肤走）
    private Bitmap faceBmp;      // 当前表情（需要回收）
    private Bitmap cur;          // 当前显示的那张
    private Bitmap rotCache;     // cur 的 90° 版本，趴边用，按需生成

    private int skinIdx = 0, faceCur = 0;
    private boolean[] faceOk = new boolean[0];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int srcW = 944, srcH = 1200;
    private int headX0, headX1, headY0, headY1;
    /** 同一块裁剪区，但换算成「没旋转的原图」坐标（趴屏幕下边时用） */
    private int headBX0, headBX1, headBY0, headBY1;

    private int state = STATE_HOVER;
    private boolean mirrorX = false;
    private int perchEdge = EDGE_LEFT;
    private float hoverHeightDp = 240f, perchWidthDp = 96f, ampScale = 0.75f;

    private float pDy, pSx = 1f, pSy = 1f;

    private float dragTilt;
    private long startNs, lastFrameNs, tapNs;
    private long frameIntervalNs = (long) (NS / 30);
    private boolean windowVisible = true, looping = false;

    private Listener listener;
    private float downRawX, downRawY, lastRawX, lastRawY;
    private boolean dragging, longPressed;
    private int touchSlop;

    private final Runnable longPressRun = new Runnable() {
        @Override public void run() {
            longPressed = true;
            if (listener != null) listener.onLongPress();
        }
    };

    public PetView(Context c) { super(c); init(c); }
    public PetView(Context c, AttributeSet a) { super(c, a); init(c); }

    private void init(Context c) {
        ctx = c;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        paint.setFilterBitmap(true);
        paint.setDither(true);
        applySkin(0);
    }

    private Bitmap decode(String name) {
        if (name == null) return null;
        try {
            InputStream is = ctx.getAssets().open(name);
            Bitmap b = BitmapFactory.decodeStream(is);
            is.close();
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean assetExists(String name) {
        try { ctx.getAssets().open(name).close(); return true; }
        catch (Exception e) { return false; }
    }

    private static Bitmap rotate90(Bitmap src) {
        Matrix m = new Matrix();
        m.postRotate(90);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private void clearRot() {
        if (rotCache != null && !rotCache.isRecycled()) rotCache.recycle();
        rotCache = null;
    }

    // ---------------- 皮肤 ----------------

    public void applySkin(int idx) {
        if (idx < 0 || idx >= SKINS.length) return;
        Skin s = SKINS[idx];
        if (faceBmp != null && !faceBmp.isRecycled()) faceBmp.recycle();
        faceBmp = null;
        clearRot();
        if (baseBmp != null && !baseBmp.isRecycled()) baseBmp.recycle();

        skinIdx = idx;
        faceCur = 0;
        baseBmp = decode(s.base);

        // 只认真正存在的表情文件 —— 这样素材没到齐也不会切到空白
        faceOk = new boolean[s.faces.length];
        for (int i = 0; i < s.faces.length; i++) faceOk[i] = assetExists(s.faces[i]);

        cur = baseBmp;
        if (cur != null) {
            srcW = cur.getWidth();
            srcH = cur.getHeight();
            // ↓ 这组是「旋转 90° 之后的图」坐标：x 用 srcH 且翻转、y 用 srcW
            headX0 = Math.round(srcH - 1 - s.hy1 * srcH);
            headX1 = Math.round(srcH - 1 - s.hy0 * srcH);
            headY0 = Math.round(s.hx0 * srcW);
            headY1 = Math.round(s.hx1 * srcW);

            // ↓ 同一块区域换算回「原图坐标」，趴屏幕下边（不旋转）时必须用这组。
            //   否则矩形会冲出原图右边界（basin: x 要到 1181，原图只有 992 宽），
            //   画出来只剩左边一半。
            headBX0 = Math.round(s.hx0 * srcW);
            headBX1 = Math.round(s.hx1 * srcW);
            headBY0 = Math.round(s.hy0 * srcH);
            headBY1 = Math.round(s.hy1 * srcH);
        }
        requestLayout();
        invalidate();
    }

    public void cycleSkin() { applySkin((skinIdx + 1) % SKINS.length); }

    public int skinCount() { return SKINS.length; }
    public int currentSkin() { return skinIdx; }
    public String skinName() { return SKINS[skinIdx].name; }
    public String skinName(int i) { return (i >= 0 && i < SKINS.length) ? SKINS[i].name : ""; }

    // ---------------- 表情 ----------------

    public void setFace(int idx) {
        Skin s = SKINS[skinIdx];
        if (idx < 0 || idx > s.faces.length || idx == faceCur) return;
        if (idx == 0) {
            if (faceBmp != null && !faceBmp.isRecycled()) faceBmp.recycle();
            faceBmp = null;
            cur = baseBmp;
        } else {
            if (!faceOk[idx - 1]) return;
            Bitmap b = decode(s.faces[idx - 1]);
            if (b == null) return;
            if (faceBmp != null && !faceBmp.isRecycled()) faceBmp.recycle();
            faceBmp = b;
            cur = b;
        }
        faceCur = idx;
        clearRot();
        invalidate();
    }

    public void cycleFace() {
        int n = SKINS[skinIdx].faces.length;
        for (int i = 1; i <= n; i++) {
            int idx = (faceCur + i) % (n + 1);
            if (idx == 0 || faceOk[idx - 1]) { setFace(idx); return; }
        }
    }

    public int faceCount() {
        int n = 0;
        for (boolean b : faceOk) if (b) n++;
        return n;
    }

    public int currentFace() { return faceCur; }

    public void pokeFeedback() { tapNs = System.nanoTime(); invalidate(); }

    public void setListener(Listener l) { this.listener = l; }

    // ---- 自动溜达：Service 挪窗口，这里只管"走路的样子" ----
    private boolean walking = false;
    private long lastTouchMs = System.currentTimeMillis();

    public void setWalking(boolean w) {
        if (walking != w) { walking = w; invalidate(); }
    }

    /** 距离上一次碰她过了多久（毫秒）—— 刚碰过就先别乱跑 */
    public long msSinceTouch() { return System.currentTimeMillis() - lastTouchMs; }

    public boolean isDragging() { return dragging; }

    public void setState(int s) {
        if (state != s) {
            state = s;
            frameIntervalNs = (long) (NS / (s == STATE_PERCH ? 20 : 30));
            requestLayout();
            invalidate();
            if (s == STATE_HOVER) startLoop();
        }
    }

    public int getState() { return state; }
    public void setMirror(boolean m) { mirrorX = m; invalidate(); }

    public void setPerchEdge(int e) { perchEdge = e; requestLayout(); invalidate(); }
    public int getPerchEdge() { return perchEdge; }

    public void setHoverHeightDp(float v) { hoverHeightDp = v; requestLayout(); invalidate(); }
    public void setPerchWidthDp(float v) { perchWidthDp = v; requestLayout(); invalidate(); }
    public float getHoverHeightDp() { return hoverHeightDp; }
    public float getPerchWidthDp() { return perchWidthDp; }
    public void setAmpScale(float v) { ampScale = Math.max(0.1f, v); invalidate(); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public float windowW() { return dp(hoverHeightDp) * srcW / (float) srcH; }
    public float windowH() { return dp(hoverHeightDp) * 1.16f; }
    public float perchWindowW() {
        // 趴下边：滑块给的是脑袋"高"，宽度由长宽比推出来
        if (perchEdge == EDGE_BOTTOM) {
            float a = (headBX1 - headBX0) / (float) (headBY1 - headBY0);
            return dp(perchWidthDp) * a * 1.10f;
        }
        return dp(perchWidthDp) * 1.10f * SKINS[skinIdx].perchSideScale;
    }

    /** 趴下边时她实际占的高度（用来把下巴精确落在屏幕下沿） */
    public float perchVisibleH() { return dp(perchWidthDp); }
    public float perchWindowH() {
        // 趴下边：滑块＝脑袋高度，窗口只需容下"呼吸"那点伸缩
        if (perchEdge == EDGE_BOTTOM) return dp(perchWidthDp) * 1.14f;
        // 趴左右：图转了 90°，用旋转坐标的长宽比
        return dp(perchWidthDp) * (headY1 - headY0) / (float) (headX1 - headX0) * 1.16f
                * SKINS[skinIdx].perchSideScale;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (state == STATE_HOVER) setMeasuredDimension(Math.round(windowW()), Math.round(windowH()));
        else setMeasuredDimension(Math.round(perchWindowW()), Math.round(perchWindowH()));
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (cur == null) return;
        long now = System.nanoTime();
        if (startNs == 0) { startNs = now; lastFrameNs = now; }
        float t = (now - startNs) / NS;

        final float tau = (float) (2 * Math.PI);
        // 扒边时她应该安静贴着，不再浮沉/呼吸/点动
        boolean still = state == STATE_PERCH;
        pDy = still ? 0f : 0.022f * ampScale * (float) Math.sin(tau * t / 2.4f);
        pSx = 1f;
        pSy = still ? 1f : 1f + 0.014f * ampScale * (float) Math.sin(tau * 2 * t / 2.4f);

        if (!still && dragging) {
            pSx += 0.05f * Math.abs(dragTilt) / 9f;
            pSy -= 0.02f * Math.abs(dragTilt) / 9f;
        }
        if (!still && walking) {
            // 挪动时左右摇 + 上下颠。只平移不摇的话，看着像贴纸在滑，不像在走。
            float wk = tau * t * 1.9f;
            pDy += 0.016f * ampScale * Math.abs((float) Math.sin(wk));
            pSy *= 1f + 0.018f * ampScale * (float) Math.sin(wk * 2f);
            dragTilt = 5.5f * (float) Math.sin(wk);
        } else if (!dragging) {
            dragTilt = 0f;
        }
        if (!still && tapNs != 0) {
            float k = (now - tapNs) / (0.30f * NS);
            if (k >= 1f) tapNs = 0;
            else pSy *= 1f - 0.10f * (float) Math.sin(Math.PI * k);
        }

        if (state == STATE_HOVER) drawHover(canvas);
        else drawPerch(canvas, t);
    }

    private void drawHover(Canvas canvas) {
        float h = dp(hoverHeightDp);
        float w = h * srcW / (float) srcH;
        float ww = getWidth(), hh = getHeight();
        float cx = ww / 2f;
        float bottom = hh - h * 0.08f + pDy * h;
        float top = bottom - h * pSy;
        float halfW = w * pSx / 2f;

        RectF dst = new RectF(cx - halfW, top, cx + halfW, bottom);
        canvas.save();
        canvas.rotate(dragging ? dragTilt : 0f, cx, hh - h * 0.10f);
        canvas.drawBitmap(cur, null, dst, paint);
        canvas.restore();
    }

    private void drawPerch(Canvas canvas, float t) {
        if (cur == null) return;

        int bw0 = headBX1 - headBX0, bh0 = headBY1 - headBY0;
        // ---- 趴屏幕下边：不旋转，脑袋从下沿探出来 ----
        if (perchEdge == EDGE_BOTTOM) {
            // 滑块值＝脑袋高度（和趴左右时同一视觉尺寸），宽度按长宽比推
            float h0 = dp(perchWidthDp) * pSy;
            float w0 = h0 * bw0 / (float) bh0;
            float left = (getWidth() - w0) / 2f;
            float top = pDy * h0;                        // 贴着窗口顶（窗口顶＝屏幕下沿往上一点）
            canvas.drawBitmap(cur, new Rect(headBX0, headBY0, headBX1, headBY1),
                    new RectF(left, top, left + w0, top + h0), paint);
            return;
        }
        if (rotCache == null || rotCache.isRecycled()) rotCache = rotate90(cur);
        int bw = headX1 - headX0, bh = headY1 - headY0;
        // 侧边：和 perchWindowW/H 用同一个系数，两边必须一起缩，只改窗口会把她裁掉
        float w = dp(perchWidthDp) * SKINS[skinIdx].perchSideScale;
        float h = w * bh / (float) bw;
        float peek = 0f;   // 扒边时不动，连探头的小动作也省掉
        float hh2 = h * pSy;
        float top = (getHeight() - hh2) / 2f + pDy * h;

        Rect src = new Rect(headX0, headY0, headX1, headY1);
        RectF dst = new RectF(peek, top, peek + w, top + hh2);

        canvas.save();
        if (mirrorX) canvas.scale(-1f, 1f, getWidth() / 2f, 0f);
        canvas.drawBitmap(rotCache, src, dst, paint);
        canvas.restore();
    }

    // ---------------- 渲染循环：不可见时真的停 ----------------

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!windowVisible) { looping = false; return; }
        // 扒边时静止：停掉帧循环，省电；点她换表情时 invalidate 仍会画一帧
        if (state == STATE_PERCH) { looping = false; return; }
        if (frameTimeNanos - lastFrameNs >= frameIntervalNs) {
            lastFrameNs = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); startLoop(); }
    @Override protected void onDetachedFromWindow() { looping = false; super.onDetachedFromWindow(); }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = (visibility == VISIBLE);
        if (windowVisible) startLoop();
    }

    public void onScreenOff() { windowVisible = false; looping = false; }
    public void onScreenOn() { windowVisible = true; startLoop(); }

    private void startLoop() {
        if (looping || !windowVisible) return;
        looping = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    // ---------------- 触摸 ----------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float rx = e.getRawX(), ry = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = lastRawX = rx;
                downRawY = lastRawY = ry;
                dragging = false;
                longPressed = false;
                dragTilt = 0f;
                lastTouchMs = System.currentTimeMillis();
                handler.postDelayed(longPressRun, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (Math.abs(rx - downRawX) > touchSlop || Math.abs(ry - downRawY) > touchSlop) {
                    handler.removeCallbacks(longPressRun);
                }
                if (!dragging && (Math.abs(rx - downRawX) > touchSlop
                        || Math.abs(ry - downRawY) > touchSlop)) {
                    dragging = true;
                    if (listener != null) listener.onDragStart();
                }
                if (dragging) {
                    lastTouchMs = System.currentTimeMillis();
                    dragTilt = Math.max(-9f, Math.min(9f, dragTilt + (rx - lastRawX) * 0.35f));
                    dragTilt *= 0.85f;
                    if (listener != null) listener.onDrag(rx - lastRawX, ry - lastRawY);
                    lastRawX = rx;
                    lastRawY = ry;
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRun);
                if (dragging) {
                    dragging = false;
                    dragTilt = 0f;
                    if (listener != null) listener.onDragEnd(rx, ry);
                } else {
                    cycleFace();
                    tapNs = System.nanoTime();
                    invalidate();
                    if (listener != null) listener.onTap();
                }
                longPressed = false;
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }
}
