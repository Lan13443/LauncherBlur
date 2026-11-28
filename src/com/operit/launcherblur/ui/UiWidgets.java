package com.operit.launcherblur.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

public final class UiWidgets {

    private UiWidgets() {
    }

    public static int dp(Context ctx, float val) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, val, ctx.getResources().getDisplayMetrics()));
    }

    public static float dpF(Context ctx, float val) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, val, ctx.getResources().getDisplayMetrics());
    }

    public static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = Color.alpha(a), ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int ba = Color.alpha(b), br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.argb(
                Math.round(aa + (ba - aa) * t),
                Math.round(ar + (br - ar) * t),
                Math.round(ag + (bg - ag) * t),
                Math.round(ab + (bb - ab) * t)
        );
    }

    // =========================================================================
    // BackdropStageLayout: live backdrop capture for frosted blur top bar
    // =========================================================================
    public static class BackdropStageLayout extends FrameLayout {
        private RenderNode mStageNode;
        private int mBgColor;
        private View mTopOverlay;

        public BackdropStageLayout(Context ctx, int bgColor) {
            super(ctx);
            mBgColor = bgColor;
            setBackgroundColor(bgColor);
            if (Build.VERSION.SDK_INT >= 29) {
                mStageNode = new RenderNode("LauncherBlurBackdropStage");
            }
        }

        public void setTopOverlay(View top) {
            mTopOverlay = top;
        }

        public RenderNode getStageNode() {
            if (Build.VERSION.SDK_INT >= 29 && mStageNode != null && mStageNode.hasDisplayList()) {
                return mStageNode;
            }
            return null;
        }

        private void notifyOverlay() {
            if (mTopOverlay != null) {
                mTopOverlay.invalidate();
            }
        }

        @Override
        public void onDescendantInvalidated(View child, View target) {
            super.onDescendantInvalidated(child, target);
            invalidate();
            notifyOverlay();
        }

        @Override
        @SuppressWarnings("deprecation")
        public android.view.ViewParent invalidateChildInParent(int[] location, Rect dirty) {
            android.view.ViewParent vp = super.invalidateChildInParent(location, dirty);
            invalidate();
            notifyOverlay();
            return vp;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()
                    && mStageNode != null && w > 0 && h > 0) {
                try {
                    mStageNode.setPosition(0, 0, w, h);
                    RecordingCanvas rc = mStageNode.beginRecording(w, h);
                    rc.drawColor(mBgColor);
                    super.dispatchDraw(rc);
                    mStageNode.endRecording();
                    canvas.drawRenderNode(mStageNode);
                    return;
                } catch (Throwable ignored) {
                }
            }
            super.dispatchDraw(canvas);
        }
    }

    // =========================================================================
    // BlurTopBarView: translucent collapsing RenderEffect top bar
    // =========================================================================
    public static class BlurTopBarView extends View {
        public interface OnRefreshListener {
            void onRefreshClicked();
        }

        private ThemeEngine mTheme;
        private BackdropStageLayout mBackdropStage;
        private RenderNode mBlurNode;
        private float mScrollFade = 0f;
        private String mTitle = "LauncherBlur";
        private OnRefreshListener mListener;
        private int mStatusBarInset = 0;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mArcRect = new RectF();

        public BlurTopBarView(Context ctx, ThemeEngine theme) {
            super(ctx);
            mTheme = theme;
            if (Build.VERSION.SDK_INT >= 29) {
                mBlurNode = new RenderNode("LauncherBlurTopBarBlur");
            }
        }

        public void setBackdropStage(BackdropStageLayout stage) {
            mBackdropStage = stage;
        }

        public void setStatusBarInset(int inset) {
            mStatusBarInset = inset;
            requestLayout();
        }

        public void setTitle(String title) {
            mTitle = title != null ? title : "";
            invalidate();
        }

        public void setScrollFade(float fade) {
            float clamped = Math.max(0f, Math.min(1f, fade));
            if (Math.abs(mScrollFade - clamped) > 0.005f) {
                mScrollFade = clamped;
                invalidate();
            }
        }

        public void setOnRefreshListener(OnRefreshListener l) {
            mListener = l;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = mStatusBarInset + dp(getContext(), 54);
            setMeasuredDimension(w, h);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            if (y < mStatusBarInset) return false;
            float hitW = dpF(getContext(), 64);
            boolean hitRefresh = x >= (getWidth() - hitW);

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                return hitRefresh || mScrollFade > 0.25f;
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                if (hitRefresh && mListener != null) {
                    mListener.onRefreshClicked();
                    return true;
                }
            }
            return hitRefresh;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (mScrollFade > 0.02f) {
                RenderNode stageNode = (mBackdropStage != null) ? mBackdropStage.getStageNode() : null;
                if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()
                        && mBlurNode != null && stageNode != null) {
                    try {
                        mBlurNode.setPosition(0, 0, w, h);
                        RecordingCanvas rc = mBlurNode.beginRecording(w, h);
                        rc.drawColor(mTheme.bgColor);
                        rc.save();
                        rc.translate(-getLeft(), -getTop());
                        rc.drawRenderNode(stageNode);
                        rc.restore();
                        mBlurNode.endRecording();

                        float radius = dpF(getContext(), 22f) * Math.min(1f, mScrollFade * 1.3f);
                        if (radius > 1f) {
                            mBlurNode.setRenderEffect(RenderEffect.createBlurEffect(
                                    radius, radius, Shader.TileMode.CLAMP));
                        } else {
                            mBlurNode.setRenderEffect(null);
                        }
                        canvas.save();
                        canvas.clipRect(0, 0, w, h);
                        canvas.drawRenderNode(mBlurNode);
                        canvas.restore();
                    } catch (Throwable ignored) {
                    }
                }

                int overlayAlpha = (int) (172 * mScrollFade);
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(ThemeEngine.withAlpha(mTheme.bgColor, overlayAlpha));
                canvas.drawRect(0, 0, w, h, mPaint);

                mPaint.setColor(ThemeEngine.withAlpha(mTheme.dividerColor, (int) (40 * mScrollFade)));
                canvas.drawRect(0, h - 1.5f, w, h, mPaint);
            }

            float barCenterY = mStatusBarInset + (h - mStatusBarInset) * 0.5f;

            // Refresh icon on top-right
            float cx = w - dpF(getContext(), 28);
            float r = dpF(getContext(), 8.5f);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dpF(getContext(), 2.0f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setColor(mTheme.textPrimary);
            mArcRect.set(cx - r, barCenterY - r, cx + r, barCenterY + r);
            canvas.drawArc(mArcRect, 35, 290, false, mPaint);

            float tipX = (float) (cx + r * Math.cos(Math.toRadians(325)));
            float tipY = (float) (barCenterY + r * Math.sin(Math.toRadians(325)));
            canvas.drawLine(tipX, tipY, tipX - dpF(getContext(), 4.5f), tipY - dpF(getContext(), 1f), mPaint);
            canvas.drawLine(tipX, tipY, tipX + dpF(getContext(), 1f), tipY - dpF(getContext(), 4.8f), mPaint);

            if (mScrollFade > 0.08f && !mTitle.isEmpty()) {
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setFakeBoldText(true);
                mPaint.setTextSize(dpF(getContext(), 19));
                mPaint.setColor(ThemeEngine.withAlpha(mTheme.textPrimary, (int) (255 * mScrollFade)));
                Paint.FontMetrics fm = mPaint.getFontMetrics();
                float ty = barCenterY - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(mTitle, dpF(getContext(), 20), ty, mPaint);
            }
        }
    }

    // =========================================================================
    // HeroStatusCardView: Monet Hero status summary card with watermark
    // =========================================================================
    public static class HeroStatusCardView extends View {
        private ThemeEngine mTheme;
        private boolean mBlurActive = true;
        private boolean mNoParallax = true;
        private int mRadius = 60;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Path mClipPath = new Path();
        private final Path mCheckPath = new Path();

        public HeroStatusCardView(Context ctx, ThemeEngine theme) {
            super(ctx);
            mTheme = theme;
            setClickable(true);
            setFocusable(true);
        }

        public void updateState(ThemeEngine theme, boolean blurActive, boolean noParallax, int radius) {
            mTheme = theme;
            mBlurActive = blurActive;
            mNoParallax = noParallax;
            mRadius = radius;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(w, dp(getContext(), 132));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float radius = dpF(getContext(), 24);
            mRect.set(0, 0, w, h);

            mClipPath.reset();
            mClipPath.addRoundRect(mRect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mClipPath);

            boolean active = mBlurActive || mNoParallax;
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(active ? mTheme.heroCardBg : mTheme.cardElevatedColor);
            canvas.drawRoundRect(mRect, radius, radius, mPaint);

            // Right watermark: blur halo ring + checkmark or pause bars
            float cx = w - dpF(getContext(), 30);
            float cy = h - dpF(getContext(), 24);
            float outerR = dpF(getContext(), 50);

            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dpF(getContext(), 2.2f));
            mPaint.setColor(ThemeEngine.withAlpha(active ? mTheme.heroWatermarkColor : mTheme.textTertiary, 55));
            canvas.drawCircle(cx, cy, dpF(getContext(), 64), mPaint);

            mPaint.setStrokeWidth(dpF(getContext(), 7.5f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setColor(active ? mTheme.heroWatermarkColor : ThemeEngine.withAlpha(mTheme.textTertiary, 90));
            canvas.drawCircle(cx, cy, outerR, mPaint);

            if (active) {
                mCheckPath.reset();
                mCheckPath.moveTo(cx - dpF(getContext(), 20), cy + dpF(getContext(), 2));
                mCheckPath.lineTo(cx - dpF(getContext(), 5), cy + dpF(getContext(), 16));
                mCheckPath.lineTo(cx + dpF(getContext(), 24), cy - dpF(getContext(), 14));
                canvas.drawPath(mCheckPath, mPaint);
            } else {
                float barOffset = dpF(getContext(), 9);
                float barH = dpF(getContext(), 14);
                canvas.drawLine(cx - barOffset, cy - barH, cx - barOffset, cy + barH, mPaint);
                canvas.drawLine(cx + barOffset, cy - barH, cx + barOffset, cy + barH, mPaint);
            }

            float padX = dpF(getContext(), 22);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(active ? mTheme.heroTextPrimary : mTheme.textPrimary);
            mPaint.setTextSize(dpF(getContext(), 20.5f));
            mPaint.setFakeBoldText(true);
            String title;
            if (mBlurActive && mNoParallax) {
                title = "模糊与锁定均已就绪";
            } else if (mBlurActive) {
                title = "桌面模糊已启用";
            } else if (mNoParallax) {
                title = "桌面壁纸已锁定无视差";
            } else {
                title = "增强功能已暂停";
            }
            canvas.drawText(title, padX, dpF(getContext(), 42), mPaint);

            mPaint.setFakeBoldText(false);
            mPaint.setColor(active ? mTheme.heroTextSecondary : mTheme.textSecondary);
            mPaint.setTextSize(dpF(getContext(), 13.5f));
            String sub = "视差: " + (mNoParallax ? "已关闭 (对齐基准)" : "已开启 (随页滑动)")
                    + " · 模糊强度: " + (mBlurActive ? (mRadius + " px") : "关");
            canvas.drawText(sub, padX, dpF(getContext(), 67), mPaint);

            mPaint.setFakeBoldText(true);
            mPaint.setColor(active ? mTheme.heroTextPrimary : mTheme.textSecondary);
            mPaint.setTextSize(dpF(getContext(), 14.5f));
            canvas.drawText("LauncherBlur v1.2 · 点击查看实时 JSON", padX, h - dpF(getContext(), 20), mPaint);

            canvas.restore();
        }
    }

    // =========================================================================
    // ModernSwitchView: Miuix / Material 3 Pill Switch
    // =========================================================================
    public static class ModernSwitchView extends View {
        public interface OnCheckedChangeListener {
            void onCheckedChanged(ModernSwitchView view, boolean isChecked);
        }

        private ThemeEngine mTheme;
        private boolean mChecked;
        private float mProgress;
        private ValueAnimator mAnim;
        private OnCheckedChangeListener mListener;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        public ModernSwitchView(Context ctx, ThemeEngine theme, boolean initialChecked) {
            super(ctx);
            mTheme = theme;
            mChecked = initialChecked;
            mProgress = initialChecked ? 1f : 0f;
            setClickable(true);
            setFocusable(true);
        }

        public boolean isChecked() {
            return mChecked;
        }

        public void setChecked(boolean checked, boolean animate, boolean notify) {
            if (mChecked == checked && !notify) {
                float target = checked ? 1f : 0f;
                if (Math.abs(mProgress - target) < 0.01f) return;
            }
            mChecked = checked;
            float target = checked ? 1f : 0f;
            if (mAnim != null) mAnim.cancel();
            if (animate) {
                mAnim = ValueAnimator.ofFloat(mProgress, target);
                mAnim.setDuration(220);
                mAnim.setInterpolator(new OvershootInterpolator(1.1f));
                mAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mProgress = (Float) animation.getAnimatedValue();
                        invalidate();
                    }
                });
                mAnim.start();
            } else {
                mProgress = target;
                invalidate();
            }
            if (notify && mListener != null) {
                mListener.onCheckedChanged(this, mChecked);
            }
        }

        public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
            mListener = l;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (isEnabled()) {
                setChecked(!mChecked, true, true);
            }
            return true;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(dp(getContext(), 52), dp(getContext(), 30));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float p = Math.max(0f, Math.min(1f, mProgress));
            int trackColor = lerpColor(mTheme.switchTrackOff, mTheme.switchTrackOn, p);
            int thumbColor = lerpColor(mTheme.switchThumbOff, mTheme.switchThumbOn, p);

            mRect.set(0, 0, w, h);
            float r = h * 0.5f;
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(trackColor);
            canvas.drawRoundRect(mRect, r, r, mPaint);

            if (p < 0.95f) {
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dpF(getContext(), 1.2f));
                mPaint.setColor(ThemeEngine.withAlpha(mTheme.switchStrokeOff, (int) ((1f - p) * 65)));
                float inset = dpF(getContext(), 0.7f);
                mRect.set(inset, inset, w - inset, h - inset);
                canvas.drawRoundRect(mRect, r, r, mPaint);
            }

            float thumbRadiusOff = dpF(getContext(), 9.0f);
            float thumbRadiusOn = dpF(getContext(), 11.0f);
            float thumbR = thumbRadiusOff + (thumbRadiusOn - thumbRadiusOff) * p;

            float startX = h * 0.5f;
            float endX = w - h * 0.5f;
            float cx = startX + (endX - startX) * mProgress;
            float cy = h * 0.5f;

            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(thumbColor);
            canvas.drawCircle(cx, cy, thumbR, mPaint);
        }
    }

    // =========================================================================
    // ModernSliderView: Thick Capsule Slider with Press Halo
    // =========================================================================
    public static class ModernSliderView extends View {
        public interface OnValueChangeListener {
            void onValueChanged(int value, boolean fromUser);
        }

        private ThemeEngine mTheme;
        private final int mMin;
        private final int mMax;
        private int mValue;
        private OnValueChangeListener mListener;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final int mTouchSlop;
        private float mDownX = 0f;
        private float mDownY = 0f;
        private boolean mDragging = false;

        public ModernSliderView(Context ctx, ThemeEngine theme, int min, int max, int current) {
            super(ctx);
            mTheme = theme;
            mMin = min;
            mMax = Math.max(min + 1, max);
            mValue = Math.max(min, Math.min(max, current));
            mTouchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        }

        public int getValue() {
            return mValue;
        }

        public void setValue(int val, boolean notify) {
            int clamped = Math.max(mMin, Math.min(mMax, val));
            if (mValue != clamped) {
                mValue = clamped;
                invalidate();
                if (notify && mListener != null) {
                    mListener.onValueChanged(mValue, false);
                }
            }
        }

        public void setOnValueChangeListener(OnValueChangeListener l) {
            mListener = l;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(w, dp(getContext(), 36));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                mDownX = event.getX();
                mDownY = event.getY();
                mDragging = false;
                return true;
            } else if (action == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(event.getX() - mDownX);
                float dy = Math.abs(event.getY() - mDownY);
                if (!mDragging) {
                    if (dy > mTouchSlop && dy > dx) {
                        return false;
                    }
                    if (dx > mTouchSlop) {
                        mDragging = true;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        updateFromTouch(event.getX());
                    }
                } else {
                    updateFromTouch(event.getX());
                }
                return true;
            } else if (action == MotionEvent.ACTION_UP) {
                if (mDragging) {
                    mDragging = false;
                    invalidate();
                } else {
                    float dy = Math.abs(event.getY() - mDownY);
                    if (dy <= mTouchSlop) {
                        updateFromTouch(event.getX());
                    }
                }
                return true;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                mDragging = false;
                invalidate();
                return true;
            }
            return super.onTouchEvent(event);
        }

        private void updateFromTouch(float x) {
            float pad = dpF(getContext(), 12);
            float trackW = Math.max(1f, getWidth() - pad * 2f);
            float ratio = Math.max(0f, Math.min(1f, (x - pad) / trackW));
            int newVal = mMin + Math.round(ratio * (mMax - mMin));
            if (newVal != mValue) {
                mValue = newVal;
                invalidate();
                if (mListener != null) {
                    mListener.onValueChanged(mValue, true);
                }
            } else {
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float pad = dpF(getContext(), 12);
            float trackH = dpF(getContext(), 12);
            float top = (h - trackH) * 0.5f;
            float bottom = top + trackH;
            float r = trackH * 0.5f;

            mRect.set(pad, top, w - pad, bottom);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mTheme.switchTrackOff);
            canvas.drawRoundRect(mRect, r, r, mPaint);

            float ratio = (float) (mValue - mMin) / (float) (mMax - mMin);
            float activeRight = pad + Math.max(trackH, (w - pad * 2f) * ratio);
            mRect.set(pad, top, activeRight, bottom);
            mPaint.setColor(mTheme.primary);
            canvas.drawRoundRect(mRect, r, r, mPaint);

            float thumbX = pad + (w - pad * 2f) * ratio;
            float thumbY = h * 0.5f;
            float thumbR = mDragging ? dpF(getContext(), 11.5f) : dpF(getContext(), 10f);

            if (mDragging) {
                mPaint.setColor(ThemeEngine.withAlpha(mTheme.primary, 55));
                canvas.drawCircle(thumbX, thumbY, dpF(getContext(), 17f), mPaint);
            }

            mPaint.setColor(mTheme.primary);
            canvas.drawCircle(thumbX, thumbY, thumbR, mPaint);
            mPaint.setColor(mTheme.onPrimary);
            canvas.drawCircle(thumbX, thumbY, thumbR * 0.44f, mPaint);
        }
    }

    // =========================================================================
    // SectionIconView: Crisp vector icons inside soft Monet icon badges
    // =========================================================================
    public static class SectionIconView extends View {
        public static final int ICON_PARALLAX = 1;
        public static final int ICON_BLUR = 2;
        public static final int ICON_GRID = 3;
        public static final int ICON_RECENTS = 4;
        public static final int ICON_RESUME = 5;
        public static final int ICON_EYE_OFF = 6;
        public static final int ICON_WARNING = 7;

        private final int mType;
        private final int mBadgeColor;
        private final int mIconColor;
        private final boolean mWithBadge;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Path mPath = new Path();

        public SectionIconView(Context ctx, int type, int badgeColor, int iconColor, boolean withBadge) {
            super(ctx);
            mType = type;
            mBadgeColor = badgeColor;
            mIconColor = iconColor;
            mWithBadge = withBadge;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int sz = dp(getContext(), mWithBadge ? 38 : 24);
            setMeasuredDimension(sz, sz);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cx = w * 0.5f;
            float cy = h * 0.5f;

            if (mWithBadge) {
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(mBadgeColor);
                canvas.drawCircle(cx, cy, Math.min(w, h) * 0.5f, mPaint);
            }

            mPaint.setColor(mIconColor);
            mPaint.setStrokeWidth(dpF(getContext(), 1.85f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);

            switch (mType) {
                case ICON_PARALLAX: {
                    // Phone frame + horizontal parallax bi-directional arrow
                    mPaint.setStyle(Paint.Style.STROKE);
                    float fw = dpF(getContext(), 6.5f);
                    float fh = dpF(getContext(), 8.5f);
                    mRect.set(cx - fw, cy - fh, cx + fw, cy + fh);
                    canvas.drawRoundRect(mRect, dpF(getContext(), 2.5f), dpF(getContext(), 2.5f), mPaint);
                    float aw = dpF(getContext(), 9.2f);
                    canvas.drawLine(cx - aw, cy, cx + aw, cy, mPaint);
                    float tip = dpF(getContext(), 2.8f);
                    mPath.reset();
                    mPath.moveTo(cx - aw + tip, cy - tip);
                    mPath.lineTo(cx - aw, cy);
                    mPath.lineTo(cx - aw + tip, cy + tip);
                    canvas.drawPath(mPath, mPaint);
                    mPath.reset();
                    mPath.moveTo(cx + aw - tip, cy - tip);
                    mPath.lineTo(cx + aw, cy);
                    mPath.lineTo(cx + aw - tip, cy + tip);
                    canvas.drawPath(mPath, mPaint);
                    break;
                }
                case ICON_BLUR: {
                    mPaint.setStyle(Paint.Style.FILL);
                    for (int ix = -2; ix <= 2; ix++) {
                        for (int iy = -2; iy <= 2; iy++) {
                            float dist = (float) Math.hypot(ix, iy);
                            if (dist <= 2.3f) {
                                float dotR = dpF(getContext(), Math.max(0.7f, 1.75f - dist * 0.42f));
                                canvas.drawCircle(cx + ix * dpF(getContext(), 3.2f),
                                        cy + iy * dpF(getContext(), 3.2f), dotR, mPaint);
                            }
                        }
                    }
                    break;
                }
                case ICON_GRID: {
                    mPaint.setStyle(Paint.Style.FILL);
                    float cr = dpF(getContext(), 1.5f);
                    float off = dpF(getContext(), 4.2f);
                    float half = dpF(getContext(), 3.0f);
                    canvas.drawRoundRect(new RectF(cx - off - half, cy - off - half, cx - off + half, cy - off + half), cr, cr, mPaint);
                    canvas.drawRoundRect(new RectF(cx + off - half, cy - off - half, cx + off + half, cy - off + half), cr, cr, mPaint);
                    canvas.drawRoundRect(new RectF(cx - off - half, cy + off - half, cx - off + half, cy + off + half), cr, cr, mPaint);
                    canvas.drawRoundRect(new RectF(cx + off - half, cy + off - half, cx + off + half, cy + off + half), cr, cr, mPaint);
                    break;
                }
                case ICON_RECENTS: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    float cw = dpF(getContext(), 4.8f);
                    float ch = dpF(getContext(), 6.8f);
                    canvas.drawRoundRect(new RectF(cx - cw - dpF(getContext(), 3.2f), cy - ch + dpF(getContext(), 1.5f),
                            cx + cw - dpF(getContext(), 3.2f), cy + ch + dpF(getContext(), 1.5f)), dpF(getContext(), 2f), dpF(getContext(), 2f), mPaint);
                    mPaint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(new RectF(cx - cw + dpF(getContext(), 2.5f), cy - ch - dpF(getContext(), 1.2f),
                            cx + cw + dpF(getContext(), 2.5f), cy + ch - dpF(getContext(), 1.2f)), dpF(getContext(), 2.2f), dpF(getContext(), 2.2f), mPaint);
                    break;
                }
                case ICON_RESUME: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    float r = dpF(getContext(), 7.5f);
                    canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 40, 270, false, mPaint);
                    float ax = cx + (float) (r * Math.cos(Math.toRadians(310)));
                    float ay = cy + (float) (r * Math.sin(Math.toRadians(310)));
                    canvas.drawLine(ax, ay, ax - dpF(getContext(), 4f), ay - dpF(getContext(), 0.5f), mPaint);
                    canvas.drawLine(ax, ay, ax + dpF(getContext(), 0.5f), ay - dpF(getContext(), 4f), mPaint);
                    break;
                }
                case ICON_EYE_OFF: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    float ew = dpF(getContext(), 8.5f);
                    float eh = dpF(getContext(), 5.2f);
                    mPath.reset();
                    mPath.moveTo(cx - ew, cy);
                    mPath.quadTo(cx, cy - eh * 1.4f, cx + ew, cy);
                    mPath.quadTo(cx, cy + eh * 1.4f, cx - ew, cy);
                    canvas.drawPath(mPath, mPaint);
                    canvas.drawCircle(cx, cy, dpF(getContext(), 2.6f), mPaint);
                    canvas.drawLine(cx - dpF(getContext(), 7.5f), cy + dpF(getContext(), 7.5f),
                            cx + dpF(getContext(), 7.5f), cy - dpF(getContext(), 7.5f), mPaint);
                    break;
                }
                case ICON_WARNING: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    float r = dpF(getContext(), 8.5f);
                    canvas.drawCircle(cx, cy, r, mPaint);
                    canvas.drawLine(cx, cy - dpF(getContext(), 4.2f), cx, cy + dpF(getContext(), 1.2f), mPaint);
                    mPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy + dpF(getContext(), 4.4f), dpF(getContext(), 1.1f), mPaint);
                    break;
                }
            }
        }
    }
}
