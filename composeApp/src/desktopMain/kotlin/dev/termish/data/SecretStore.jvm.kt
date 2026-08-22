package dev.termish.data

import java.io.File
import java.util.Properties

/**
 * 桌面（开发/测试）存储：以 Properties 文件保存在用户目录。
 * 注意：仅用于桌面测试目标，不具备与移动端同级的密钥保护。
 */
actual object SecretStore {
    private fun file(): File {
        val dir = File(System.getProperty("user.home"), ".termish")
        dir.mkdirs()
        lockDown(dir, directory = true)
        return File(dir, "secrets.properties")
    }

    private fun load(): Properties {
        val p = Properties()
        val f = file()
        if (f.exists()) {
            f.inputStream().use { p.load(it) }
        }
        return p
    }

    private fun save(p: Properties) {
        val f = file()
        f.outputStream().use { p.store(it, "Termish desktop secrets (dev only)") }
        // 明文存储但收紧权限：避免同机其他用户可读（POSIX 0600；Windows 上无效果）
        lockDown(f, directory = false)
    }

    private fun lockDown(
        f: File,
        directory: Boolean,
    ) {
        try {
            f.setReadable(false, false)
            f.setReadable(true, true)
            f.setWritable(false, false)
            f.setWritable(true, true)
            f.setExecutable(false, false)
            if (directory) f.setExecutable(true, true)
        } catch (_: Exception) {
        }
    }

    actual fun get(
        service: String,
        account: String,
    ): String? = load().getProperty("$service/$account")

    actual fun set(
        service: String,
        account: String,
        value: String,
    ) {
        val p = load()
        p.setProperty("$service/$account", value)
        save(p)
    }

    actual fun delete(
        service: String,
        account: String,
    ) {
        val p = load()
        p.remove("$service/$account")
        save(p)
    }
}
