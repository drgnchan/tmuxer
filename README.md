# tmuxer

一个通过 SSH 管理远程 tmux 会话的 Android 客户端，也可以直接创建和使用 Pi 工作区。

## 主要功能

- 密码或私钥 SSH 登录
- 自动发现并切换 tmux session/window
- 后台或进程回收后自动重连
- 内置终端，支持中文、Emoji、ANSI 色彩和触摸滚动
- 深色、浅色终端主题
- 创建普通 Shell 或全屏 Pi 工作区
- Pi 快捷键、消息导航和内联图片预览
- 通过 SFTP 上传文件并自动填入远程路径

## 远程要求

- SSH 服务
- `tmux`
- 文件上传需要 SFTP
- Pi 图片预览需要 `script` 或 `python3`

应用不会修改远程 `tmux.conf`。临时上传和图片数据保存在 `~/.cache/tmuxer/`，旧文件会自动清理。

## 构建

需要 JDK 17 和 Android SDK 36：

```bash
./gradlew :app:assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

运行测试：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```
