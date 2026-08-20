# SFTP（ssh/SftpSession）

> **English summary:** the platform-neutral `SftpSession` contract (list / mkdir /
> home / upload / download), the sshj implementation for JVM and the libssh2
> one for iOS — including the longentry→stat fallback for servers without
> `readdir` long entries, the binary-safe upload workaround, the download
> progress callback, and how the UI layer does recursive cross-directory
> search, streaming download (Android straight to Downloads / iOS Files
> export / desktop chooser), recursive folder download, and **text preview**
> (streams first 512 KB via the download chunk callback, NUL-byte binary
> detection, truncation hint).

## 设计

SFTP 是**独立的 SSH 连接 + SFTP 通道**：不复用终端会话（断开终端不影响
SFTP），但认证 / 主机密钥确认复用同一套 `SshCallbacks`——弹窗由 UI 层全局
处理，用户无感知。

```kotlin
interface SftpSession {
    fun list(path: String): List<SftpEntry>   // 失败抛异常，UI 层提示
    fun mkdir(path: String)
    fun home(): String                        // realpath(".") 解析用户主目录
    fun upload(remotePath: String, content: ByteArray)
    fun download(
        remotePath: String,
        onProgress: (loaded: Long, total: Long) -> Unit = { _, _ -> },  // 进度
        onChunk: (ByteArray) -> Unit,                                   // 阻塞分块回调
    )
    fun close()
}
```

`SftpEntry` 是平台无关的目录条目（名称 / 目录标志 / 权限串 / 大小 / 时间 /
隐藏），权限串统一格式化为 `drwxr-xr-x` 供 UI 展示。

## JVM 实现（SftpSessionSshj）

复用 `SshSessionSshj` 的认证链路（密码 / 私钥 / 加密私钥口令 / KBI / TOFU），
`openSftp()` 在已认证连接上开 `SFTPClient`（懒初始化，`clientOrThrow`）。

- **list**：`c.ls(path)` 映射属性（`FileMode.Type.DIRECTORY` 判目录，
  permissions 集合 → `drwxr-xr-x` 串）
- **home**：`canonicalize(".")`——比猜 `/home/xxx` 通用（macOS / BSD /
  自定义 home 全覆盖）
- **upload**：包一个 `LocalSourceFile`（内存 `ByteArrayInputStream`，
  权限 0644）走 sshj 的 `put` 传输
- **download**：`open(remotePath)` + 按 offset 读 64KB 分块回调——**流式**，
  不整文件驻留内存；`onProgress` 用 `remote.length()` 报总大小；由 UI 层写
  本地文件并 close 保存通道

## iOS 实现（SftpSessionLibssh2）

`ssh.openSftp()` 在**阻塞模式**（`session_set_blocking(1)`）下 `libssh2_sftp_init`，
`libssh2_sftp_*` 全部阻塞调用 + EAGAIN 重试。

### readdir：longentry 解析 + stat 兜底（最易踩的坑）

cinterop 里 `LIBSSH2_SFTP_ATTRIBUTES` 不透明，拿不到文件类型。libssh2 的
`readdir_ex` 同时返回 longentry（`drwxr-xr-x 2 root root 4096 Jan 1 12:34 name`），
从那里解析权限 / 大小 / 类型：

- **服务器不返回 longentry**（Windows OpenSSH、部分嵌入式 SFTP）→ 类型未知，
  逐条目 `stat_ex` 兜底拿真实类型——**递归目录下载依赖准确的 isDirectory**，
  判错会拿目录当文件下载
- stat 也失败 → 按普通文件处理（下载/进入时自然会再报错），不中断整个列表

### 二进制上传：termish_sftp_write 原生辅助

`libssh2_sftp_write` 的 cinterop 映射只暴露 `String` 版本（UTF-8 编码），
二进制内容经它必损坏。`nativeInterop/cinterop/sftp_write.h` 用
`unsigned char*` 重新声明：

```c
static inline long termish_sftp_write(LIBSSH2_SFTP_HANDLE *handle,
                                      const unsigned char *buffer, size_t count)
```

cinterop 对 `unsigned char*` 生成指针参数，`usePinned` 拿字节地址直接写。
32KB 分块 + EAGAIN 重试，`0x1A4`（0644）+ TRUNC。

### 其他

- **home**：`symlink_ex(".", …, LIBSSH2_SFTP_REALPATH)` 解析主目录
- **mkdir**：`0x1ED`（0755）
- **download**：64KB 分块 `sftp_read` 回调，与 JVM 侧同语义；`onProgress`
  用 `fstat_ex` 拿总大小

## UI 层（SftpScreen / SftpHostPickerOverlay）

- 连接覆盖层 → 文件浏览 tab；**面包屑导航**（两级折叠 + 返回=历史回退）；
  **递归搜索**（跨目录，文件名 + 相对路径扁平展示，点击跳转所在目录；
  限深 8 层 / 限 500 结果）；切 tab 保持路径 / 列表状态（`SftpUiState` 随
  `SftpSessionEntry` 存活）
- 上传：系统文件选择器（SAF / UIDocumentPicker / AWT）
- **文本预览**（文件菜单 → 预览）：`readSftpPreview` 复用 download 分块回调，
  只读前 512 KB（超过即抛 `SftpPreviewTooLargeException` 中断下载流，两平台
  download 的 finally 都会关通道）；前 4 KB 含 NUL 判二进制抛
  `SftpPreviewBinaryException`；UTF-8 解码（非法序列替换字符不崩）。
  UI：全屏模态覆盖层（文件名 + 大小 + 关闭），LazyColumn 等宽渲染，
  截断时顶部提示条；状态挂在 `SftpUiState`（切 tab 不丢）
- 下载：**流式分块写本地文件**（不整文件驻留内存）+ 顶部进度横幅（文件名 /
  百分比 / 进度条）+ 下载完成系统通知（Android 点击打开文件）；Android 10+
  直接写公共 Download 目录（MediaStore，同名自动去重、不弹另存为），
  Android 9- 回退 SAF；iOS 写临时文件下载完弹 Files 转存；桌面文件选择器
- **递归目录下载**：`DirectorySaver` 逐文件/子目录展开，目录识别依赖
  `isDirectory` 的准确性（见上）
- 会话与终端会话平级管理（`SessionManager.sftpSessions`）：连接页可见、
  卡片 Close 可关、删除主机连带释放

## 测试

- `commonTest/ui/SftpLogicTest`：UI 层纯逻辑（路径拼接 / 递归展开 / 递归搜索）
- `desktopTest/ssh/SftpIntegrationTest`：打真实 sshd 的上传 / 下载 / 递归
  目录往返（self-detect，sshd 缺席 SKIP）
- iOS 的 longentry→stat 兜底路径无自动化覆盖——涉及 `SftpSessionLibssh2.kt`
  的改动需真机验证（尤其 Windows OpenSSH 服务器场景）
