package com.operit.launcherblur;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.operit.launcherblur.ui.ThemeEngine;
import com.operit.launcherblur.ui.UiWidgets;
import com.operit.launcherblur.ui.UiWidgets.BackdropStageLayout;
import com.operit.launcherblur.ui.UiWidgets.BlurTopBarView;
import com.operit.launcherblur.ui.UiWidgets.HeroStatusCardView;
import com.operit.launcherblur.ui.UiWidgets.ModernSliderView;
import com.operit.launcherblur.ui.UiWidgets.ModernSwitchView;
import com.operit.launcherblur.ui.UiWidgets.SectionIconView;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String GRANT_CMD =
            "adb shell pm grant com.operit.launcherblur android.permission.WRITE_SECURE_SETTINGS";

    // Config state (preserving all 11 launcherblur_cfg keys)
    private boolean enabled = true;
    private int radius = 60;
    private int fadeIn = 140;
    private int fadeOut = 120;
    private int hold = 80;
    private boolean content = true;
    private int contentRadius = -1;
    private boolean overview = true;
    private int overviewRadius = -1;
    private boolean resumeBg = false;
    private boolean noParallax = true; // default locked (parallax switch OFF)

    private ThemeEngine mTheme;
    private BackdropStageLayout mBackdropStage;
    private BlurTopBarView mTopBar;
    private ScrollView mScrollView;
    private LinearLayout mContentCol;

    private int mStatusBarInset = 0;
    private int mNavBarInset = 0;
    private int mLastScrollY = 0;

    // Live view references
    private LinearLayout mWarningCard;
    private HeroStatusCardView mHeroCard;

    private ModernSwitchView mSwParallax;
    private ModernSwitchView mSwEnabled;
    private ModernSliderView mSlRadius;
    private TextView mBadgeRadius;
    private View mBlockRadius;

    private ModernSwitchView mSwContent;
    private View mRowContentFollow;
    private ModernSwitchView mSwContentFollow;
    private View mBlockContentRadius;
    private ModernSliderView mSlContentRadius;
    private TextView mBadgeContentRadius;

    private ModernSwitchView mSwOverview;
    private View mRowOverviewFollow;
    private ModernSwitchView mSwOverviewFollow;
    private View mBlockOverviewRadius;
    private ModernSliderView mSlOverviewRadius;
    private TextView mBadgeOverviewRadius;

    private LinearLayout mAnimCard;
    private ModernSliderView mSlFadeIn;
    private TextView mBadgeFadeIn;
    private ModernSliderView mSlHold;
    private TextView mBadgeHold;
    private ModernSliderView mSlFadeOut;
    private TextView mBadgeFadeOut;

    private ModernSwitchView mSwResumeBg;
    private ModernSwitchView mSwHideIcon;

    private TextView mStatusHintTv;
    private TextView mStatusJsonTv;
    private String mLastStatusHeader = "已读取系统配置";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        load();
        buildUi();
        syncUi(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        updateWarningVisibility();
        syncUi(false);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mScrollView != null) {
            mLastScrollY = mScrollView.getScrollY();
        }
        configureEdgeToEdgeWindow();
        buildUi();
        syncUi(false);
        if (mScrollView != null && mLastScrollY > 0) {
            mScrollView.post(new Runnable() {
                @Override
                public void run() {
                    if (mScrollView != null) {
                        mScrollView.scrollTo(0, mLastScrollY);
                    }
                }
            });
        }
    }

    private void configureEdgeToEdgeWindow() {
        mTheme = new ThemeEngine(this);
        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setNavigationBarContrastEnforced(false);
        View decor = w.getDecorView();
        WindowInsetsController ctrl = decor.getWindowInsetsController();
        if (ctrl != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ctrl.setSystemBarsAppearance(mTheme.isDark ? 0 : mask, mask);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(mTheme.bgColor);

        mBackdropStage = new BackdropStageLayout(this, mTheme.bgColor);
        root.addView(mBackdropStage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mScrollView = new ScrollView(this);
        mScrollView.setVerticalScrollBarEnabled(false);
        mScrollView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        mContentCol = new LinearLayout(this);
        mContentCol.setOrientation(LinearLayout.VERTICAL);
        mScrollView.addView(mContentCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mBackdropStage.addView(mScrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mTopBar = new BlurTopBarView(this, mTheme);
        mTopBar.setBackdropStage(mBackdropStage);
        mTopBar.setTitle("LauncherBlur");
        mTopBar.setOnRefreshListener(new BlurTopBarView.OnRefreshListener() {
            @Override
            public void onRefreshClicked() {
                load();
                mLastStatusHeader = "已从系统刷新最新配置";
                updateWarningVisibility();
                syncUi(true);
                Toast.makeText(MainActivity.this, "已刷新配置", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(mTopBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        mBackdropStage.setTopOverlay(mTopBar);

        mScrollView.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                float fade = Math.max(0f, Math.min(1f, scrollY / (float) dp(56)));
                if (mTopBar != null) {
                    mTopBar.setScrollFade(fade);
                }
            }
        });

        populateContent();
        setContentView(root);
        mContentCol.setFocusableInTouchMode(true);
        mContentCol.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        mContentCol.requestFocus();
        mScrollView.post(new Runnable() {
            @Override
            public void run() {
                if (mScrollView != null) {
                    mScrollView.scrollTo(0, 0);
                }
            }
        });

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                mStatusBarInset = bars.top;
                mNavBarInset = bars.bottom;
                applyInsetsPadding();
                return insets;
            }
        });
        applyInsetsPadding();
    }

    private void applyInsetsPadding() {
        int topSafe = (mStatusBarInset > 0 ? mStatusBarInset : dp(34)) + dp(46);
        int bottomSafe = (mNavBarInset > 0 ? mNavBarInset : dp(20)) + dp(24);
        if (mTopBar != null) {
            mTopBar.setStatusBarInset(mStatusBarInset > 0 ? mStatusBarInset : dp(34));
        }
        if (mContentCol != null) {
            mContentCol.setPadding(dp(16), topSafe, dp(16), bottomSafe);
        }
    }

    private void populateContent() {
        // Page Large Header
        TextView titleTv = new TextView(this);
        titleTv.setText("LauncherBlur");
        titleTv.setTextColor(mTheme.textPrimary);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setPadding(dp(4), dp(4), dp(4), dp(4));
        mContentCol.addView(titleTv);

        TextView subTv = new TextView(this);
        subTv.setText("Pixel 桌面高斯模糊与壁纸视差 · 免重启即时生效");
        subTv.setTextColor(mTheme.textSecondary);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        subTv.setPadding(dp(4), 0, dp(4), dp(16));
        mContentCol.addView(subTv);

        // Permission Warning Card (hidden when granted)
        mWarningCard = buildWarningCard();
        mContentCol.addView(mWarningCard, cardMargin(14));
        updateWarningVisibility();

        // Hero Status Card
        mHeroCard = new HeroStatusCardView(this, mTheme);
        mHeroCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showJsonDialog();
            }
        });
        mContentCol.addView(mHeroCard, cardMargin(8));

        // Section 1: 壁纸与视差
        mContentCol.addView(buildSectionHeader("壁纸与视差"));
        LinearLayout parallaxCard = createRoundedCard();
        mSwParallax = addIconSwitchRow(
                parallaxCard,
                SectionIconView.ICON_PARALLAX,
                "桌面壁纸滑动视差",
                "关闭时固定在「壁纸与个性化」基准位置并与全屏 AOD/锁屏对齐；开启后恢复左右滑动视差",
                !noParallax,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        noParallax = !isChecked;
                        save();
                        syncUi(false);
                    }
                }
        );
        mContentCol.addView(parallaxCard, cardMargin(8));

        // Section 2: 桌面模糊
        mContentCol.addView(buildSectionHeader("桌面模糊"));
        LinearLayout blurCard = createRoundedCard();
        mSwEnabled = addIconSwitchRow(
                blurCard,
                SectionIconView.ICON_BLUR,
                "启用桌面模糊",
                "打开或退出应用时平滑渲染桌面背景高斯模糊",
                enabled,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        enabled = isChecked;
                        save();
                        syncUi(false);
                    }
                }
        );
        addDivider(blurCard);

        SliderHolders radHold = addSliderBlock(
                blurCard,
                "壁纸模糊强度",
                "桌面展开应用或后台时的主背景模糊半径",
                0, 150, radius, "px",
                new ModernSliderView.OnValueChangeListener() {
                    @Override
                    public void onValueChanged(int value, boolean fromUser) {
                        if (!fromUser) return;
                        radius = value;
                        updateRadiusBadges();
                        save();
                        refreshHero();
                    }
                }
        );
        mBlockRadius = radHold.block;
        mSlRadius = radHold.slider;
        mBadgeRadius = radHold.badge;

        addDivider(blurCard);
        mSwContent = addIconSwitchRow(
                blurCard,
                SectionIconView.ICON_GRID,
                "图标与小部件一起模糊",
                "启动应用期间同步对桌面图标层应用模糊效果",
                content,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        content = isChecked;
                        save();
                        syncUi(false);
                    }
                }
        );
        addDivider(blurCard);

        SwitchRowHolders contentFollowHold = addPlainSwitchRow(
                blurCard,
                "图标强度跟随壁纸",
                "开启后图标与小部件采用与壁纸相同的模糊强度",
                contentRadius <= 0,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        if (isChecked) {
                            contentRadius = -1;
                        } else {
                            contentRadius = Math.max(1, radius);
                        }
                        save();
                        syncUi(false);
                    }
                }
        );
        mRowContentFollow = contentFollowHold.row;
        mSwContentFollow = contentFollowHold.sw;

        SliderHolders contentRadHold = addSliderBlock(
                blurCard,
                "图标独立强度",
                "仅关闭“跟随壁纸”时生效",
                0, 150, contentRadius > 0 ? contentRadius : radius, "px",
                new ModernSliderView.OnValueChangeListener() {
                    @Override
                    public void onValueChanged(int value, boolean fromUser) {
                        if (!fromUser) return;
                        contentRadius = Math.max(1, value);
                        updateRadiusBadges();
                        save();
                    }
                }
        );
        mBlockContentRadius = contentRadHold.block;
        mSlContentRadius = contentRadHold.slider;
        mBadgeContentRadius = contentRadHold.badge;
        mContentCol.addView(blurCard, cardMargin(12));

        // Overview Card
        LinearLayout overviewCard = createRoundedCard();
        mSwOverview = addIconSwitchRow(
                overviewCard,
                SectionIconView.ICON_RECENTS,
                "接管后台壁纸模糊",
                "进入最近任务卡片视图时同步接管背景高斯模糊",
                overview,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        overview = isChecked;
                        save();
                        syncUi(false);
                    }
                }
        );
        addDivider(overviewCard);

        SwitchRowHolders ovFollowHold = addPlainSwitchRow(
                overviewCard,
                "后台强度跟随壁纸",
                "与桌面启动模糊保持相同深度感",
                overviewRadius <= 0,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        if (isChecked) {
                            overviewRadius = -1;
                        } else {
                            overviewRadius = Math.max(1, radius);
                        }
                        save();
                        syncUi(false);
                    }
                }
        );
        mRowOverviewFollow = ovFollowHold.row;
        mSwOverviewFollow = ovFollowHold.sw;

        SliderHolders ovRadHold = addSliderBlock(
                overviewCard,
                "后台独立强度",
                "仅关闭“跟随壁纸”时生效",
                0, 150, overviewRadius > 0 ? overviewRadius : radius, "px",
                new ModernSliderView.OnValueChangeListener() {
                    @Override
                    public void onValueChanged(int value, boolean fromUser) {
                        if (!fromUser) return;
                        overviewRadius = Math.max(1, value);
                        updateRadiusBadges();
                        save();
                    }
                }
        );
        mBlockOverviewRadius = ovRadHold.block;
        mSlOverviewRadius = ovRadHold.slider;
        mBadgeOverviewRadius = ovRadHold.badge;
        mContentCol.addView(overviewCard, cardMargin(8));

        // Section 3: 过渡动画时长
        mContentCol.addView(buildSectionHeader("过渡动画时长"));
        mAnimCard = createRoundedCard();
        SliderHolders inHold = addSliderBlock(
                mAnimCard, "启动渐入时长", "点击应用图标后背景进入模糊的速度", 0, 800, fadeIn, "ms",
                new ModernSliderView.OnValueChangeListener() {
                    @Override
                    public void onValueChanged(int value, boolean fromUser) {
                        if (!fromUser) return;
                        fadeIn = value;
                        updateRadiusBadges();
                        save();
                    }
                }
        );
        mSlFadeIn = inHold.slider;
        mBadgeFadeIn = inHold.badge;
        addDivider(mAnimCard);

        SliderHolders holdHold = addSliderBlock(
                mAnimCard, "停在模糊时长", "回到桌面后保持短暂模糊再淡出的衔接延迟", 0, 800, hold, "ms",
                new ModernSliderView.OnValueChangeListener() {
                    @Override
                    public void onValueChanged(int value, boolean fromUser) {
                        if (!fromUser) return;
                        hold = value;
                        updateRadiusBadges();
                        save();
                    }
                }
        );
        mSlHold = holdHold.slider;
        mBadgeHold = holdHold.badge;
        addDivider(mAnimCard);

        SliderHolders outHold = addSliderBlock(
                mAnimCard, "回桌面淡出时长", "退出应用返回桌面时壁纸恢复清晰的动画时间", 0, 1200, fadeOut, "ms",
                new ModernSliderView.OnValueChangeListener() {
                    @Override
                    public void onValueChanged(int value, boolean fromUser) {
                        if (!fromUser) return;
                        fadeOut = value;
                        updateRadiusBadges();
                        save();
                    }
                }
        );
        mSlFadeOut = outHold.slider;
        mBadgeFadeOut = outHold.badge;
        mContentCol.addView(mAnimCard, cardMargin(8));

        // Section 4: 其它
        mContentCol.addView(buildSectionHeader("其它"));
        LinearLayout otherCard = createRoundedCard();
        mSwResumeBg = addIconSwitchRow(
                otherCard,
                SectionIconView.ICON_RESUME,
                "回桌面也重糊壁纸（旧行为）",
                "返回桌面瞬间重新触发一次壁纸模糊后再淡出清晰",
                resumeBg,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        resumeBg = isChecked;
                        save();
                    }
                }
        );
        addDivider(otherCard);

        mSwHideIcon = addIconSwitchRow(
                otherCard,
                SectionIconView.ICON_EYE_OFF,
                "隐藏桌面图标",
                "隐藏后仍可在 LSPosed 管理器模块列表点击设置打开",
                ThemeEngine.isLauncherIconHidden(this),
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        ThemeEngine.setLauncherIconHidden(MainActivity.this, isChecked);
                        Toast.makeText(
                                MainActivity.this,
                                isChecked ? "已隐藏桌面图标（可在 LSPosed 模块列表打开）" : "已恢复桌面图标",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
        );
        mContentCol.addView(otherCard, cardMargin(16));

        // Action Pill Row
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnHome = buildPillButton("回桌面看效果", true);
        btnHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_HOME);
                startActivity(i);
            }
        });
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        leftLp.rightMargin = dp(10);
        actionRow.addView(btnHome, leftLp);

        TextView btnReset = buildPillButton("恢复默认", false);
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        actionRow.addView(btnReset, rightLp);
        mContentCol.addView(actionRow, cardMargin(14));

        // Footer JSON status card
        LinearLayout footerCard = new LinearLayout(this);
        footerCard.setOrientation(LinearLayout.VERTICAL);
        footerCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable fBg = new GradientDrawable();
        fBg.setCornerRadius(dp(18));
        fBg.setColor(mTheme.cardColor);
        fBg.setStroke(1, mTheme.cardStrokeColor);
        footerCard.setBackground(fBg);

        mStatusHintTv = new TextView(this);
        mStatusHintTv.setTextColor(mTheme.sectionTitleColor);
        mStatusHintTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        mStatusHintTv.setTypeface(Typeface.DEFAULT_BOLD);
        footerCard.addView(mStatusHintTv);

        mStatusJsonTv = new TextView(this);
        mStatusJsonTv.setTextColor(mTheme.textSecondary);
        mStatusJsonTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mStatusJsonTv.setTypeface(Typeface.MONOSPACE);
        mStatusJsonTv.setPadding(0, dp(5), 0, 0);
        footerCard.addView(mStatusJsonTv);

        footerCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText("launcherblur_cfg", toJson(), "已复制当前 JSON 配置");
            }
        });
        mContentCol.addView(footerCard, cardMargin(8));
    }

    private LinearLayout buildWarningCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(mTheme.warningBg);
        bg.setStroke(1, mTheme.warningStroke);
        card.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        SectionIconView icon = new SectionIconView(
                this, SectionIconView.ICON_WARNING, mTheme.warningBg, mTheme.warningText, false);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconLp.rightMargin = dp(10);
        top.addView(icon, iconLp);

        TextView title = new TextView(this);
        title.setText("未授予 WRITE_SECURE_SETTINGS 权限");
        title.setTextColor(mTheme.warningText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title);
        card.addView(top);

        TextView body = new TextView(this);
        body.setText("设置变更无法写入 Settings.Secure。请通过 ADB 或 root 执行：\n" + GRANT_CMD);
        body.setTextColor(mTheme.warningText);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        body.setPadding(0, dp(6), 0, dp(10));
        card.addView(body);

        TextView copyBtn = new TextView(this);
        copyBtn.setText("复制授权命令");
        copyBtn.setTextColor(mTheme.warningText);
        copyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        copyBtn.setTypeface(Typeface.DEFAULT_BOLD);
        copyBtn.setGravity(Gravity.CENTER);
        copyBtn.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable cBg = new GradientDrawable();
        cBg.setCornerRadius(dp(14));
        cBg.setStroke(dp(1), mTheme.warningStroke);
        copyBtn.setBackground(cBg);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText("grant_cmd", GRANT_CMD, "已复制授权命令");
            }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(copyBtn, btnLp);

        return card;
    }

    private void updateWarningVisibility() {
        if (mWarningCard == null) return;
        boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        mWarningCard.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    private TextView buildSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(mTheme.sectionTitleColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(10), dp(14), dp(10), dp(8));
        return tv;
    }

    private LinearLayout createRoundedCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(mTheme.cardColor);
        bg.setStroke(1, mTheme.cardStrokeColor);
        card.setBackground(bg);
        return card;
    }

    private LinearLayout.LayoutParams cardMargin(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    private void addDivider(LinearLayout card) {
        View div = new View(this);
        div.setBackgroundColor(mTheme.dividerColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(18);
        lp.rightMargin = dp(18);
        card.addView(div, lp);
    }

    private ModernSwitchView addIconSwitchRow(LinearLayout card, int iconType, String title, String subtitle,
                                            boolean initialChecked, ModernSwitchView.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(18), dp(15));

        SectionIconView icon = new SectionIconView(
                this, iconType, mTheme.iconBadgeBg, mTheme.iconBadgeFg, true);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconLp.rightMargin = dp(14);
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dp(12);

        TextView tTitle = new TextView(this);
        tTitle.setText(title);
        tTitle.setTextColor(mTheme.textPrimary);
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(tTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tSub = new TextView(this);
            tSub.setText(subtitle);
            tSub.setTextColor(mTheme.textSecondary);
            tSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tSub.setPadding(0, dp(3), 0, 0);
            textCol.addView(tSub);
        }

        row.addView(textCol, colLp);

        final ModernSwitchView sw = new ModernSwitchView(this, mTheme, initialChecked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sw.isEnabled()) {
                    sw.performClick();
                }
            }
        });
        card.addView(row);
        return sw;
    }

    private SwitchRowHolders addPlainSwitchRow(LinearLayout card, String title, String subtitle,
                                               boolean initialChecked, ModernSwitchView.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dp(14);

        TextView tTitle = new TextView(this);
        tTitle.setText(title);
        tTitle.setTextColor(mTheme.textPrimary);
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(tTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tSub = new TextView(this);
            tSub.setText(subtitle);
            tSub.setTextColor(mTheme.textSecondary);
            tSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tSub.setPadding(0, dp(3), 0, 0);
            textCol.addView(tSub);
        }

        row.addView(textCol, colLp);

        final ModernSwitchView sw = new ModernSwitchView(this, mTheme, initialChecked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sw.isEnabled()) {
                    sw.performClick();
                }
            }
        });
        card.addView(row);
        return new SwitchRowHolders(row, sw);
    }

    private SliderHolders addSliderBlock(LinearLayout card, String title, String description,
                                         int min, int max, int current, final String unit,
                                         ModernSliderView.OnValueChangeListener listener) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(18), dp(14), dp(18), dp(12));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tTitle = new TextView(this);
        tTitle.setText(title);
        tTitle.setTextColor(mTheme.textPrimary);
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(tTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tBadge = new TextView(this);
        tBadge.setText(current + " " + unit);
        tBadge.setTextColor(mTheme.onPrimaryContainer);
        tBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tBadge.setTypeface(Typeface.DEFAULT_BOLD);
        tBadge.setPadding(dp(10), dp(3), dp(10), dp(3));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(10));
        badgeBg.setColor(mTheme.primaryContainer);
        tBadge.setBackground(badgeBg);
        headerRow.addView(tBadge);

        block.addView(headerRow);

        if (description != null && !description.isEmpty()) {
            TextView tDesc = new TextView(this);
            tDesc.setText(description);
            tDesc.setTextColor(mTheme.textSecondary);
            tDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tDesc.setPadding(0, dp(3), 0, dp(5));
            block.addView(tDesc);
        }

        ModernSliderView slider = new ModernSliderView(this, mTheme, min, max, current);
        slider.setOnValueChangeListener(listener);
        block.addView(slider);

        card.addView(block);
        return new SliderHolders(block, slider, tBadge);
    }

    private TextView buildPillButton(String text, boolean isPrimary) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(isPrimary ? mTheme.onPrimary : mTheme.textPrimary);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(isPrimary ? mTheme.primary : mTheme.cardElevatedColor);
        if (!isPrimary) {
            bg.setStroke(1, mTheme.cardStrokeColor);
        }
        btn.setBackground(bg);
        return btn;
    }

    private void syncUi(boolean animate) {
        if (mSwParallax != null) mSwParallax.setChecked(!noParallax, animate, false);
        if (mSwEnabled != null) mSwEnabled.setChecked(enabled, animate, false);
        if (mSlRadius != null) mSlRadius.setValue(clamp(radius, 150), false);

        if (mSwContent != null) mSwContent.setChecked(content, animate, false);
        boolean contentFollow = contentRadius <= 0;
        if (mSwContentFollow != null) mSwContentFollow.setChecked(contentFollow, animate, false);
        if (mSlContentRadius != null) {
            mSlContentRadius.setValue(clamp(contentFollow ? radius : contentRadius, 150), false);
        }

        if (mSwOverview != null) mSwOverview.setChecked(overview, animate, false);
        boolean ovFollow = overviewRadius <= 0;
        if (mSwOverviewFollow != null) mSwOverviewFollow.setChecked(ovFollow, animate, false);
        if (mSlOverviewRadius != null) {
            mSlOverviewRadius.setValue(clamp(ovFollow ? radius : overviewRadius, 150), false);
        }

        if (mSlFadeIn != null) mSlFadeIn.setValue(clamp(fadeIn, 800), false);
        if (mSlHold != null) mSlHold.setValue(clamp(hold, 800), false);
        if (mSlFadeOut != null) mSlFadeOut.setValue(clamp(fadeOut, 1200), false);

        if (mSwResumeBg != null) mSwResumeBg.setChecked(resumeBg, animate, false);
        if (mSwHideIcon != null) {
            mSwHideIcon.setChecked(ThemeEngine.isLauncherIconHidden(this), animate, false);
        }

        // Dependent dimming & enabled states
        setBlockEnabled(mBlockRadius, mSlRadius, enabled);
        setSwitchRowEnabled(mRowContentFollow, mSwContentFollow, enabled && content);
        setBlockEnabled(mBlockContentRadius, mSlContentRadius, enabled && content && !contentFollow);
        setSwitchRowEnabled(mRowOverviewFollow, mSwOverviewFollow, enabled && overview);
        setBlockEnabled(mBlockOverviewRadius, mSlOverviewRadius, enabled && overview && !ovFollow);
        setViewTreeEnabled(mAnimCard, enabled);
        if (mAnimCard != null) mAnimCard.setAlpha(enabled ? 1.0f : 0.48f);

        updateRadiusBadges();
        refreshHero();
        refreshFooter();
    }

    private void updateRadiusBadges() {
        if (mBadgeRadius != null) mBadgeRadius.setText(radius + " px");
        if (mBadgeContentRadius != null) {
            mBadgeContentRadius.setText(contentRadius <= 0 ? ("跟随 " + radius + " px") : (contentRadius + " px"));
        }
        if (mBadgeOverviewRadius != null) {
            mBadgeOverviewRadius.setText(overviewRadius <= 0 ? ("跟随 " + radius + " px") : (overviewRadius + " px"));
        }
        if (mBadgeFadeIn != null) mBadgeFadeIn.setText(fadeIn + " ms");
        if (mBadgeHold != null) mBadgeHold.setText(hold + " ms");
        if (mBadgeFadeOut != null) mBadgeFadeOut.setText(fadeOut + " ms");
    }

    private void refreshHero() {
        if (mHeroCard != null) {
            mHeroCard.updateState(mTheme, enabled, noParallax, radius);
        }
    }

    private void refreshFooter() {
        if (mStatusHintTv != null) {
            mStatusHintTv.setText(mLastStatusHeader + "（点击此处复制）");
        }
        if (mStatusJsonTv != null) {
            mStatusJsonTv.setText(toJson().replace(",", ", "));
        }
    }

    private static void setBlockEnabled(View block, ModernSliderView slider, boolean on) {
        if (block != null) block.setAlpha(on ? 1.0f : 0.42f);
        if (slider != null) slider.setEnabled(on);
    }

    private static void setSwitchRowEnabled(View row, ModernSwitchView sw, boolean on) {
        if (row != null) row.setAlpha(on ? 1.0f : 0.42f);
        if (sw != null) sw.setEnabled(on);
    }

    private static void setViewTreeEnabled(View v, boolean on) {
        if (v == null) return;
        v.setEnabled(on);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setViewTreeEnabled(vg.getChildAt(i), on);
            }
        }
    }

    private void load() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), Prefs.KEY);
            if (s == null || s.isEmpty()) return;
            JSONObject j = new JSONObject(s);
            enabled = j.optInt("enabled", 1) != 0;
            radius = clamp(j.optInt("radius", radius), 300);
            fadeIn = clamp(j.optInt("fadeIn", fadeIn), 3000);
            fadeOut = clamp(j.optInt("fadeOut", fadeOut), 3000);
            hold = clamp(j.optInt("hold", hold), 3000);
            content = j.optInt("content", 1) != 0;
            contentRadius = j.optInt("contentRadius", contentRadius);
            overview = j.optInt("overview", 1) != 0;
            overviewRadius = j.optInt("overviewRadius", overviewRadius);
            resumeBg = j.optInt("resumeBg", 0) != 0;
            noParallax = j.optInt("noParallax", 1) != 0;
        } catch (Throwable ignored) {
        }
    }

    private String toJson() {
        try {
            JSONObject j = new JSONObject();
            j.put("enabled", enabled ? 1 : 0);
            j.put("radius", radius);
            j.put("fadeIn", fadeIn);
            j.put("fadeOut", fadeOut);
            j.put("hold", hold);
            j.put("content", content ? 1 : 0);
            j.put("contentRadius", contentRadius);
            j.put("overview", overview ? 1 : 0);
            j.put("overviewRadius", overviewRadius);
            j.put("resumeBg", resumeBg ? 1 : 0);
            j.put("noParallax", noParallax ? 1 : 0);
            return j.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    private void save() {
        String json = toJson();
        try {
            Settings.Secure.putString(getContentResolver(), Prefs.KEY, json);
            mLastStatusHeader = "已生效（免重启桌面）";
            updateWarningVisibility();
        } catch (SecurityException se) {
            mLastStatusHeader = "缺少权限 WRITE_SECURE_SETTINGS，设置未写入";
            updateWarningVisibility();
        } catch (Throwable t) {
            mLastStatusHeader = "写入失败：" + t.getMessage();
        }
        refreshFooter();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认配置")
                .setMessage("将恢复所有桌面模糊参数为默认值，并将「桌面壁纸滑动视差」重置为关闭（锁定基准）。是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        enabled = true;
                        radius = 60;
                        fadeIn = 140;
                        fadeOut = 120;
                        hold = 80;
                        content = true;
                        contentRadius = -1;
                        overview = true;
                        overviewRadius = -1;
                        resumeBg = false;
                        noParallax = true;
                        syncUi(true);
                        save();
                    }
                })
                .show();
    }

    private void showJsonDialog() {
        final String raw = Settings.Secure.getString(getContentResolver(), Prefs.KEY);
        final String display = (raw != null && !raw.isEmpty()) ? raw : toJson();
        new AlertDialog.Builder(this)
                .setTitle("Settings.Secure · launcherblur_cfg")
                .setMessage(display)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制 JSON", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyText("launcherblur_cfg", display, "已复制 JSON 配置");
                    }
                })
                .show();
    }

    private void copyText(String label, String text, String toastMsg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(float val) {
        return UiWidgets.dp(this, val);
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : Math.min(v, max);
    }

    private static final class SliderHolders {
        final View block;
        final ModernSliderView slider;
        final TextView badge;

        SliderHolders(View block, ModernSliderView slider, TextView badge) {
            this.block = block;
            this.slider = slider;
            this.badge = badge;
        }
    }

    private static final class SwitchRowHolders {
        final View row;
        final ModernSwitchView sw;

        SwitchRowHolders(View row, ModernSwitchView sw) {
            this.row = row;
            this.sw = sw;
        }
    }
}
