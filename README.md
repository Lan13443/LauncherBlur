# LauncherBlur —— Pixel Launcher 实时背景模糊 & 壁纸视差控制 LSPosed 模块

> **当前发布版本：`v1.2`**（2026-11-28）｜[下载最新版](https://github.com/Lan13443/LauncherBlur/releases/latest)

为 Pixel Launcher（`com.google.android.apps.nexuslauncher`）提供**启动应用 / 返回桌面 / 进入 Overview** 三场景实时高斯模糊，以及**桌面左右滑动壁纸视差开关（默认锁定在「壁纸与个性化」基准位置）**，并**内置 Material You (Monet) 单页设置前端**。

- 适用：Android 14（SDK 34）AOSP 系 ROM，Pixel Launcher
- 作用域：仅 `com.google.android.apps.nexuslauncher`，不碰 `system_server` / `zygote`
- 配置：写入 `Settings.Secure` 的 `launcherblur_cfg`（JSON），模块侧 `ContentObserver` 实时重读，**改动全部免重启桌面即时生效**

---

## ✨ 新增与核心职责

### 1. 五层职责

| 模块 / 层 | 做法 |
|---|---|
| 壁纸层（启动时） | 对 Launcher 窗口的 `SurfaceControl` 调 `setBackgroundBlurRadius`，由 `animateTo` 按 `fadeIn` 渐升 |
| 图标层（启动时） | 对 `Workspace` / `Hotseat` 视图组设 `RenderEffect`，由 `animateContentTo` 独立渐升 |
| 回桌面收尾 | 图标层淡出到 0（`fadeOut`）；壁纸默认不重糊（`resumeBg=0`），由 `mArmed` 标记控制 |
| Overview（后台） | 强度跟随启动值（`overviewRadius=-1` 时取 `radius`），渐入后 600/1150ms 两次静默 re-assert |
| 壁纸视差锁定（`noParallax`） | 默认开启无视差（`noParallax=1`）：Hook `WallpaperOffsetInterpolator` / `OffsetHandler` / `WallpaperManager`，保持分页数不变防止 `OffsetHandler` 除零，并将步进锁为 `0.0f`、水平偏移锁定在 `0.0f`（LTR）/ `1.0f`（RTL），与「壁纸与个性化」起始取景及 FullAOD/锁屏一致 |

### 2. 内置 Material You (Monet) 单页设置前端
- **整合进模块本体**：合并原独立设置面板全部 10 项模糊配置及新增「桌面壁纸滑动视差」正向开关（默认关闭=无视差，打开即恢复左右滑动视差），无需单独安装配套前端 App。
- **双入口与隐藏图标**：支持从 **LSPosed 管理器 → 模块设置**（`MODULE_SETTINGS`）与**桌面图标**（`.LauncherActivity`）进入，并在「其它」分组提供「隐藏桌面图标」切换。
- **Android 14 原生 Monet 卡片体验**：跟随系统深浅色与 `system_accent1_*` 动态壁纸取色，自带实时模糊折叠顶栏（`BlurTopBarView`）、Hero 状态概览卡、防垂直滑动误触的胶囊滑块（`ModernSliderView`）及实时 `launcherblur_cfg` JSON 复制栏。

---

## ⚙️ 配置字段（`launcherblur_cfg`）

```json
{"enabled":1,"radius":60,"fadeIn":140,"hold":80,"fadeOut":120,
 "content":1,"contentRadius":-1,"overview":1,"overviewRadius":-1,"resumeBg":0,"noParallax":1}
```

| 字段 | 范围 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | 0/1 | `1` | 桌面模糊总开关 |
| `radius` | 0–150 | `60` | 壁纸层模糊强度 |
| `fadeIn` | ms | `140` | 启动渐入时长 |
| `hold` | ms | `80` | 停在模糊的保持时长 |
| `fadeOut` | ms | `120` | 回桌面淡出时长 |
| `content` | 0/1 | `1` | 是否让图标一起模糊 |
| `contentRadius` | 0–150 / -1 | `-1` | 图标层强度；`-1` 表示跟随 `radius` |
| `overview` | 0/1 | `1` | 是否接管 Overview 背景 |
| `overviewRadius` | 0–150 / -1 | `-1` | Overview 强度；`-1` 跟随启动值 |
| `resumeBg` | 0/1 | `0` | 回桌面是否重新模糊壁纸 |
| `noParallax` | 0/1 | `1` | 桌面壁纸视差控制：`1`=锁定无视差（前端开关关闭），`0`=开启左右滑动视差（前端开关打开） |

命令行写入示例：

```bash
su -c 'settings put secure launcherblur_cfg "{\"enabled\":1,\"radius\":60,\"fadeIn\":140,\"hold\":80,\"fadeOut\":120,\"content\":1,\"contentRadius\":-1,\"overview\":1,\"overviewRadius\":-1,\"resumeBg\":0,\"noParallax\":1}"'
```

---

## 📦 安装与使用

1. 从 [Releases](../../releases) 下载最新 `LauncherBlur-v1.2.apk` 并安装（无需再装独立设置面板）。
2. 在 **LSPosed 管理器** 中启用 **LauncherBlur**，作用域勾选 **Pixel Launcher (`com.google.android.apps.nexuslauncher`)**，强制停止一次桌面使其注入。
3. 执行一次权限授权后即可在应用界面内免重启实时修改全部参数（无权限时界面也会展示一键复制命令）：

```bash
adb shell pm grant com.operit.launcherblur android.permission.WRITE_SECURE_SETTINGS
# 或通过 root shell 执行：
su -c 'pm grant com.operit.launcherblur android.permission.WRITE_SECURE_SETTINGS'
```

---

## 🐛 已知坑（踩过并已修）

- **「壁纸与个性化」调整后出现向左偏移**：PixelOS `WallpaperCropUtils.calculateCropRect` 在 LTR 下会将用户框选的 `1440×3200` 视口锚定在最左侧（`x = 0`，`mWallpaperX = 0.0f`），仅把视差冗余追加在右侧；将无视差偏移误设为 `0.5f` 会切入右侧预留区导致画面偏左。现已对齐为 `isRtl ? 1.0f : 0.0f`。
- **热切换 `noParallax` 开关不生效**：
  - `1 → 0`：Pixel Launcher `WallpaperOffsetInterpolator.syncWithScroll()` 在页数未变时不会重发 `what == 4` 更新步进；现重置 `mNumScreens = -1` 并触发 `syncWithScroll()` / `jumpToFinal()` 立即恢复。
  - `0 → 1`：`WindowManagerService` 的 `WallpaperController.setWindowWallpaperPosition()` 在 `mWallpaperX/Y` 未变化时会直接跳过更新 `mWallpaperXStep`，且 `OffsetHandler` 持有独立 `WallpaperManager` 实例；现同步重置 `OffsetHandler.mWM` 并通过 `0.5001f → 0.5f` 刷新绕开等值缓存。
- **更换壁纸 / Material You 取色重载引发 `Window Session Crash`**：主题重绘销毁旧窗口后缓存旧 Binder Token 会触发 `IllegalArgumentException: Requested window ... does not exist`。现已移除过期 Token 缓存，改为每次取当前附着且 `isBinderAlive()` 有效的窗口 Token，并监听 `ACTION_WALLPAPER_CHANGED` 重新应用固定偏移。
- **原生 PixelOS 进 Overview 会用 `backgroundBlurRadius=23` 覆盖模块写入**：必须静默补写（re-assert）。
- **`RenderEffect` 需要一次 draw 才生效**：设完必须 `invalidate()`，否则会出现「背景先糊、图标后糊」。
- **回桌面图标残留半糊**：用独立 `mContentGen` + `mEpoch`/`mApplyEpoch`/`mTries` + `mSettle`/`mGuard` 兜底清理。
- **视图引用不能永久缓存 / 进入 Overview 卡顿**：校验 `isAttachedToWindow()` 并在 Overview 期间启用 `bgOnly` 分支避免重复重绘图标层。

---

## 🛠️ 构建（无 Gradle）

本项目全部 UI 与注入逻辑均为纯 Android Framework API 实现，零第三方库依赖：

```bash
export TC=/path/to/fullaod-toolchain          # 包含 android.jar、build-tools、jdk
export KS=/path/to/fullaod.jks                # 签名密钥库（alias/pass 均为 fullaod）
bash build.sh   # javac(11) -> aapt(-S res) -> d8(min-api 34) -> zipalign -> apksigner
# 产出：out/LauncherBlur.apk
```

---

## 🔍 调试

- 日志 TAG：`LauncherBlur`（`logcat -s LauncherBlur:*`）
- 关键日志：`cfg reloaded:`、`watchdog`、`hand over`、`settle`、`state ->`、`content blur =`、`[DETACHED]`、`re-resolve`
- 测试钩子：`settings put secure launcherblur_test_stuck 1` 可伪造「armed 且卡住」，验证看门狗是否在 0.6s 内清理；用完置 0
- 配置预览：`settings put secure launcherblur_force 1` 强制预览（看门狗不干预）

## 兼容性

仅在 Redmi K60 + PixelOS（Android 14）实机验证。其他 ROM 若 `SurfaceControl`、`WallpaperOffsetInterpolator` 或 `Workspace`/`Hotseat` 结构不同，可能需要调整。
