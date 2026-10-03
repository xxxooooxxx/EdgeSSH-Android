# EdgeSSH-Android

EdgeSSH 的原生 Android 客户端：直连你自己的 SSH 服务器，不经过任何中转。

> 纯原生实现（Kotlin + Jetpack Compose），**不是 WebView 套壳**。
> 终端仿真用 Termux 的 terminal-emulator / terminal-view（Apache-2.0），
> SSH/SFTP 用 sshj（Apache-2.0）。

## 功能

- **主机管理**：新增 / 编辑 / 删除 / 搜索，密码与私钥分开保存
- **SSH 终端**：密码、keyboard-interactive、私钥登录；首次连接显示主机密钥 SHA-256 指纹，需手动确认（≈ OpenSSH known_hosts）
- **SFTP 文件管理**：浏览、上传、下载、新建文件夹、重命名、删除
- **命令片段**：常用命令一键填入终端（或直接执行）
- **凭据加密**：主机密码 / 私钥经 Android Keystore 加密后存本地，不落明文

## 构建

需要 JDK 17 + Android SDK（API 36）。

```bash
./gradlew :app:assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用本仓库 GitHub Actions 的构建产物：Actions → Android CI → 最新成功的 run → Artifacts 下载 `edgessh-debug-apk`。

## 安装

1. 下载 `app-debug.apk` 传到手机
2. 允许“安装未知应用”后点击安装
3. 打开 App，添加你的服务器（IP/域名、端口、用户名、密码或私钥）
4. 首次连接核对主机密钥指纹，确认后即可使用

## 安全说明

- App 直连你的服务器，SSH 流量不经过任何第三方。
- 密码 / 私钥只保存在本机加密存储中，卸载 App 后一并清除。
- 仓库与代码中不含任何密钥、Token。

## 开源许可

Apache License 2.0，见 [LICENSE](LICENSE)。

第三方库：
- [sshj](https://github.com/hierynomus/sshj) — Apache-2.0
- [Termux terminal-emulator / terminal-view](https://github.com/termux/termux-app) — Apache-2.0
- Jetpack Compose / AndroidX — Apache-2.0
