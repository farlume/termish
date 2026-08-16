package dev.termish

import android.app.Application
import android.content.Context
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/** 进程级 Application Context（用于无参 expect 存储）。 */
object AppContext {
    @Volatile private var context: Context? = null

    fun init(c: Context) {
        context = c.applicationContext
    }

    fun get(): Context = context ?: throw IllegalStateException("AppContext 未初始化")
}

class TermishApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        installFullBouncyCastle()
    }

    /**
     * Android 自带一个阉割版 BouncyCastle（provider 名也叫 "BC"），缺少 X25519 / Ed25519。
     * sshj 的 SecurityUtils.register() 看到 "BC" 已存在就不会再注册随 App 打包的完整版，
     * 导致 curve25519 KEX 时报 "no such algorithm: X25519 for provider BC"。
     * 这里用完整版替换掉内置阉割版。
     */
    private fun installFullBouncyCastle() {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }
}
