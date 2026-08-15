package dev.mssh.data

import java.io.File
import java.util.Properties

/**
 * 桌面（开发/测试）存储：以 Properties 文件保存在用户目录。
 * 注意：仅用于桌面测试目标，不具备与移动端同级的密钥保护。
 */
actual object SecretStore {
    private fun file(): File {
        val dir = File(System.getProperty("user.home"), ".mssh")
        dir.mkdirs()
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
        file().outputStream().use { p.store(it, "MSSH desktop secrets (dev only)") }
    }

    actual fun get(service: String, account: String): String? = load().getProperty("$service/$account")

    actual fun set(service: String, account: String, value: String) {
        val p = load()
        p.setProperty("$service/$account", value)
        save(p)
    }

    actual fun delete(service: String, account: String) {
        val p = load()
        p.remove("$service/$account")
        save(p)
    }
}
