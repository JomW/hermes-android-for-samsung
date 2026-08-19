# hermes-android for Samsung（三星增强版）

> 本仓库是 [raulvidis/hermes-android](https://github.com/raulvidis/hermes-android) 的 **fork**，针对三星 Galaxy 设备（OneUI，含国行机型）做了深度优化。
> **默认分支 `samsung`** 承载增强版本；`main` 分支与上游保持同步不变。
> English version: [README.md](README.md)

## 📱 下载 APK

从 [Releases 页面](https://github.com/JomW/hermes-android-for-samsung/releases) 下载 `hermes-android-0.5.3.apk` 安装到手机上。

- 适用系统：Android 8.0+（minSdk 26），三星 OneUI 体验最佳
- 安装包为 **debug 签名构建**，可直接安装测试；正式分发需自行用正式密钥签名
- 安装后需授予：悬浮窗、无障碍服务、使用情况访问、通知权限；如需健康数据再在「健康连接」App 中授权

## ✨ 本 fork 新增 / 改进了什么

| 功能 | 上游原版 | 本 fork（0.5.3） |
|---|---|---|
| `/current_app` 前台应用识别 | 依赖无障碍窗口快照——三星 OneUI 的 FreecessController 冻结应用后快照卡死在旧窗口，会永远误报"桌面" | 改用 **UsageStats 系统级真值** + 三级数据源回退（`ForegroundAppTracker`），冻结不影响 |
| 健康数据 | 需要"抢屏打开三星健康 → 截图 → 视觉识别"，打断用户且易误识别 | 新增 `/health` 接口，**直读 Health Connect**（步数 / 睡眠 / 心率 / 卡路里），零打扰、零误识别 |
| 最近应用 | 无 | 新增 `/recent_apps` 接口（UsageStats） |
| 锁屏判定 | 猜 systemui 包名 | 改用 **Keyguard 类名**精确判定 |
| Android 13+ 通知 | 无 | 补充 `POST_NOTIFICATIONS` 权限处理（三星没有它会锁死通知使用权，通知监听无法启用） |
| 无障碍服务 | — | OneUI 固件 bug 规避：不再在 `onServiceConnected` 中重赋值 `serviceInfo`（会破坏事件分发） |
| 中继（relay） | 心跳 15s、白名单无健康路由 | 心跳改 60s（扛得住三星应用冻结器不断线）、白名单新增 `/recent_apps` + `/health` |
| 构建工具链 | AGP 8.3.0 / Gradle 8.6 / compileSdk 34 | AGP 8.9.1 / Gradle 8.11.1 / compileSdk 36 + `health-connect-client` 1.1.0 |
| 国内构建 | 只有官方源 | 阿里云 Maven 镜像 + 腾讯 Gradle 分发加速 |
| 服务运维 | — | `contrib/` 提供中继守护进程 + 监督器（Windows NSSM / systemd），跟随 Hermes gateway 生命周期自动起停、崩溃自愈 |

详细变更记录见 [CHANGELOG.md](CHANGELOG.md)。

## 🔍 它是做什么的

给 AI 助手一双"手"：通过手机上的桥接 App + 服务端中继，让 AI 可以远程读取屏幕、执行点击/输入/滑动、获取通知/联系人/健康数据等。

```
手机（任意网络）──WebSocket──> Hermes 服务器（云端中继 8766 端口）<──HTTP── AI Agent
```

手机主动**向外**连接你的服务器——无需端口转发、无需 VPN、无需 USB，只需 6 位配对码。

### 仓库结构

| 组件 | 路径 | 语言 | 用途 |
|-----------|------|----------|---------|
| Android 桥接 App | `hermes-android-bridge/` | Kotlin | 运行在手机上，通过无障碍服务执行命令 |
| Python 工具集 | `tools/`, `tests/` | Python | 服务端 42 个 `android_*` 工具 + WebSocket 中继 |

## 📲 安装与使用

1. 下载 APK 安装到三星手机（需允许"安装未知来源应用"）
2. 打开 App，按界面提示授予：**悬浮窗、无障碍服务、使用情况访问**；Android 13+ 还需通知权限
3. **健康数据（可选）**：
   - 国行三星无 Google Health Connect 集成 → 安装免费应用 **Health Sync**（com.dmytro.healthsync），把三星健康数据同步到 Health Connect
   - 在「健康连接」App 中给 hermes-android 授权步数/睡眠/心率/卡路里读取权限
4. 在服务端配置 `ANDROID_BRIDGE_URL` 与 `ANDROID_BRIDGE_TOKEN`，输入手机 App 显示的 6 位配对码即可连接

## 🛠 从源码构建

要求：JDK 17（AGP 8.x 必需）、Android SDK（platforms 34/36）。

```bash
cd hermes-android-bridge
export JAVA_HOME=/path/to/jdk-17
export GRADLE_USER_HOME=/path/to/gradle-home   # 建议放非 C 盘，防止 C 盘爆满
./gradlew assembleDebug
```

国内网络已内置加速：阿里云 Maven 镜像 + 腾讯 Gradle 发行版镜像。

## 🌿 分支说明

- **`samsung`（默认）**：本 fork 的增强版本，建议使用此分支
- **`main`**：与上游 raulvidis/hermes-android 保持同步的原版

## ⚠️ 安全声明

这是一个**远程控制桥**：配对后拥有设备完整控制权。请妥善保管配对码与 Token，勿泄露服务器地址。详见 [SECURITY.md](SECURITY.md)。

## 📄 许可证

MIT（沿用上游）。
