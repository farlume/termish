# iOS App Store 6.5 英寸素材

Native iOS screenshots and app previews captured from Termish 1.7.1 (35).

这套素材对应 App Store Connect 的 **iPhone 6.5 英寸**栏位，修复原截图尺寸不符合该栏位要求的问题。

| 素材 | 中文 `zh/` | 英文 `en/` | 规格 |
| --- | --- | --- | --- |
| 截图 | 8 张 | 7 张 | 1242 × 2688，JPEG，无透明通道 |
| App 预览 | `app-preview.mp4` | `app-preview.mp4` | 886 × 1920，H.264 High / Level 4.0，30 fps |

中文视频 27 秒，英文视频 29 秒，均包含 Herdr 代码编辑与工作区切换片段；视频目标码率 10 Mbps，包含 48 kHz、256 kbps 双声道 AAC 静音轨。没有配音或背景音乐。

## 上传方式

1. 在 App Store Connect 选择对应版本和语言，展开 **iPhone 6.5 英寸**的预览与截屏栏位；必要时进入“查看媒体管理器”。
2. 移除这个栏位中尺寸错误的旧图片，按文件编号上传对应语言目录中的 `.jpg` 文件。
3. 把该目录的 `app-preview.mp4` 上传到 App 预览区域。截图和预览视频使用不同的像素尺寸。
4. 等待 Apple 完成视频处理，检查预览封面和截图顺序。建议把终端截图放在第一张，也可以保留当前“主机 → 终端 → 文件”的顺序。

请上传导出的原文件；浏览器或聊天窗口中的显示缩略图不用于上传。

## 内容与来源

- `01-hosts.jpg`：主机列表。
- `02-terminal.jpg`：连接本地演示 SSH 服务器后运行的真实 htop 终端。
- `03-sftp.jpg`：远程项目目录。
- `04-preview.jpg`：Markdown 文件预览，仅中文套装提供。
- `05-settings.jpg`：设置。
- `06-theme.jpg`：终端配色与字体。
- `07-herdr-editor.jpg`：Herdr 工作区内的 Vim 代码编辑器。
- `08-herdr-workspace.jpg`：Herdr 工作区和标签切换。

截图直接来自 iPhone 11 Pro Max 模拟器，iOS 26.5，应用版本 1.7.1（35）。演示数据与真实主机信息隔离；未对界面进行重绘、裁切或拉伸。视频从实际录屏剪辑并转换到 Apple 要求的尺寸，静态页面末尾保留阅读停顿。

英文 Markdown 预览目前仍有“预览 / 源码”两个中文按钮，所以英文套装未收录此页。应用代码未在本次素材制作中修改。

Herdr 截图来自真实运行的演示工作区，包含 Editor 和 Health Check 两个标签。Herdr 自身的界面为英文，因此两套素材中的 Herdr 菜单均显示英文。

上传前核对各文件的尺寸、格式、时长和编码，上传后确认 App Store Connect 的处理结果。

## Apple 规格

- [截图规格](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications)：6.5 英寸支持 1242 × 2688 或 1284 × 2778 竖屏截图。
- [App 预览规格](https://developer.apple.com/help/app-store-connect/reference/app-information/app-preview-specifications/)：6.5 英寸竖屏视频为 886 × 1920，15–30 秒，最高 30 fps。

规格核对日期：2026-09-05。
