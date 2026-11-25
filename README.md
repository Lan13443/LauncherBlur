# LauncherBlur —— Pixel Launcher 实时背景模糊 LSPosed 模块

为 Pixel Launcher（`com.google.android.apps.nexuslauncher`）补上**启动应用 / 返回桌面 / 进入 Overview**
三个场景的实时高斯模糊，壁纸层与图标层分别独立控制强度与动画。

- 适用：Android 14（SDK 34）AOSP 系 ROM，Pixel Launcher
- 作用域：仅 `com.google.android.apps.nexuslauncher`，不碰 system_server / zygote
- 配置：写入 `Settings.Secure` 的 `launcherblur_cfg`（JSON），模块侧 ContentObserver 实时重读，**改参数不需要重启桌面**

## 四层职责

| 层 | 做法 |
|---|---|
| 壁纸层（启动时） | 对 Launcher 窗口的 `SurfaceControl` 调 `setBackgroundBlurRadius`，由 `animateTo` 按 `fadeIn` 渐升 |
| 图标层（启动时） | 对 `Workspace` / `Hotseat` 视图组设 `RenderEffect`，由 `animateContentTo` 独立渐升 |
| 回桌面收尾 | 图标层淡出到 0（`fadeOut`）；壁纸默认不重糊（`resumeBg=0`），由 `mArmed` 标记控制 |
| Overview（后台） | 强度跟随启动值（`overviewRadius=-1` 时取 `radius`），渐入后 600/1150ms 两次静默 re-assert |

## 配置字段（`launcherblur_cfg`）

```json
{"enabled":1,"radius":60,"fadeIn":140,"hold":80,"fadeOut":120,
 "content":1,"contentRadius":-1,"overview":1,"overviewRadius":-1,"resumeBg":0}
```

| 字段 | 范围 | 说明 |
|---|---|---|
| `enabled` | 0/1 | 总开关 |
| `radius` | 0–150 | 壁纸层模糊强度 |
| `fadeIn` | ms | 启动渐入时长 |
| `hold` | ms | 停在模糊的保持时长 |
| `fadeOut` | ms | 回桌面淡出时长 |
| `content` | 0/1 | 是否让图标一起模糊 |
| `contentRadius` | 0–150 / -1 | 图标层强度；`-1` 表示跟随 `radius` |
| `overview` | 0/1 | 是否接管 Overview 背景 |
| `overviewRadius` | 0–150 / -1 | Overview 强度；`-1` 跟随启动值 |
| `resumeBg` | 0/1 | 回桌面是否重新模糊壁纸 |

写入示例：

```bash
su -c 'settings put secure launcherblur_cfg "{\"enabled\":1,\"radius\":60,...}"'
```

## 安装

1. 安装 APK
2. LSPosed 中启用 **LauncherBlur**，作用域勾选 **Pixel Launcher**
3. 强制停止 Launcher（或重启）使其注入
4. 首次写入配置需要 `WRITE_SECURE_SETTINGS`；可配合参数面板或 `su -c settings put` 直接写

## 已知坑（踩过并已修）

- **原生 PixelOS 进 Overview 会用 `backgroundBlurRadius=23` 覆盖模块写入**，必须静默补写（re-assert）
- **`RenderEffect` 需要一次 draw 才生效**，设完必须 `invalidate()`，否则会出现「背景先糊、图标后糊」
- **回桌面图标残留半糊**：两个根因 —— `mArmed` 卡死导致 `mApplyRun` 提前 return 且无人清理；图标淡出复用壁纸的 `mGen` 被打断。现已用独立 `mContentGen` + `mEpoch`/`mApplyEpoch`/`mTries` + `mSettle`/`mGuard` 兜底
- **视图引用不能永久缓存**：Launcher 重建视图后旧引用 detach，写入会「日志有、画面无」。现在校验 `isAttachedToWindow()`，失效则重新解析
- **进入 Overview 卡顿**：曾因每一级阶梯都重设 RenderEffect 导致全屏重绘约 27 次。已加 `bgOnly` 分支，Overview 期间完全不碰图标层

## 构建（无 Gradle）

```bash
bash build.sh   # javac(11) -> d8(min-api 34) -> aapt -> zipalign -> apksigner
```

脚本里 `SDK` / `KS`（签名密钥库）路径是本机绝对路径，换机器需自行改；产物默认名为 `out/LauncherBlur-v1.0.apk`。

## 调试

- 日志 TAG：`LauncherBlur`（`logcat -s LauncherBlur:*`）
- 关键日志：`watchdog`、`hand over`、`settle`、`state ->`、`content blur =`、`[DETACHED]`、`re-resolve`
- 测试钩子：`settings put secure launcherblur_test_stuck 1` 可伪造「armed 且卡住」，验证看门狗是否在 0.6s 内清理；用完置 0
- 配置预览：`settings put secure launcherblur_force 1` 强制预览（看门狗不干预）

## 兼容性

仅在 Redmi K60 + PixelOS（Android 14）实机验证。其他 ROM 若 `SurfaceControl` 或 `Workspace`/`Hotseat` 结构不同，可能需要调整。
