# tmuxer

[![Build](https://github.com/drgnchan/tmuxer/actions/workflows/android.yml/badge.svg)](https://github.com/drgnchan/tmuxer/actions/workflows/android.yml)

一个通过 SSH 管理远程 tmux 会话的 Android 客户端，也可以直接创建和使用 Pi 工作区。

## 主要功能

- 密码或私钥 SSH 登录
- 自动发现并切换 tmux session/window
- 后台或进程回收后自动重连
- 内置终端，支持中文、Emoji、ANSI 色彩和触摸滚动
- 双击终端打开键盘，长按选择并复制文本
- 支持 OSC 52 远程剪贴板，Pi 回复可通过 `^X` 原格式复制到 Android
- 深色、浅色终端主题
- 创建普通 Shell 或可指定远程工作目录的全屏 Pi 工作区，支持启动时立即提交 Prompt、Skill 命令、正常任务和不保存会话的临时任务，并按主机保留最近目录
- 按主机保存 Pi 快捷任务模板，通过 `{{input}}` 填入少量参数后一键创建并进入会话
- Pi 快捷键、消息导航和按需图片浮窗预览
- 通过 SFTP 上传文件并自动填入远程路径

## 远程要求

- SSH 服务
- `tmux`
- 文件上传需要 SFTP
- Pi 图片预览无需额外守护进程或图片服务

应用不会修改远程 `tmux.conf`。图片按内容哈希保存到 `~/.cache/tmuxer/images/`，主 tmux 输出只包含一行 OSC 8 链接，不需要额外图片流；点击后才会通过 SFTP 下载并打开浮窗。相同图片可跨会话复用，旧缓存会自动清理。

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

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

终端渲染器参考了开源的 [Termux](https://github.com/termux/termux-app)（GPL-3.0）实现思路，但未包含其任何源码或二进制。应用内置 **Ubuntu Mono Regular** 字体，遵循 Ubuntu Font Licence 1.0，详见 [OPEN_SOURCE_NOTICES.md](OPEN_SOURCE_NOTICES.md)。
