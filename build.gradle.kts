import org.gradle.process.ExecOperations
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// ---------------------------------------------------------------------------
// 本地工作流统一入口（CI 与 Makefile 复用同一组任务，避免流程知识三处散落）
// ---------------------------------------------------------------------------

/** 支持 configuration cache 的多命令任务基类：通过注入的 ExecOperations 执行外部命令。 */
abstract class ExecTask @Inject constructor() : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    fun run(vararg cmd: String, ignoreExit: Boolean = false) {
        execOps.exec {
            commandLine(*cmd)
            isIgnoreExitValue = ignoreExit
        }
    }
}

/** 启动本地测试 sshd（幂等：已在监听则跳过）。集成测试按 2222 端口可用性自动跳过。 */
abstract class StartTestSshdTask @Inject constructor() : ExecTask() {
    @TaskAction
    fun start() {
        // 与 scripts/test-sshd.sh 及集成测试保持一致（Termish_TEST_PORT 默认 22222）；
        // 不可写死 2222——本机/CI 可能有其他服务占用（gitlab 等）导致误判已在监听
        val port = System.getenv("Termish_TEST_PORT")?.toIntOrNull() ?: 22222
        val up = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) }
        }.isSuccess
        if (up) {
            logger.lifecycle("sshd 已在 127.0.0.1:$port 监听，跳过")
        } else {
            run("bash", "scripts/test-sshd.sh")
        }
    }
}
tasks.register<StartTestSshdTask>("startTestSshd") {
    group = "verification"
    description = "启动本地测试 sshd（127.0.0.1:2222），已在运行则跳过"
}

/** 传输层集成测试：自动启动 sshd 后跑 desktopTest（测试按 sshd/mosh 可用性自我探测）。 */
tasks.register("testIntegration") {
    group = "verification"
    description = "SSH/Mosh 集成测试（自动起 sshd；单测一拼跑）"
    dependsOn("startTestSshd", ":composeApp:desktopTest")
}

/** 构建 + 安装 debug 到模拟器/设备并启动 App。 */
abstract class RunDebugTask @Inject constructor() : ExecTask() {
    @TaskAction
    fun launch() {
        run("adb", "shell", "am", "force-stop", "dev.termish.app", ignoreExit = true)
        run("adb", "shell", "monkey", "-p", "dev.termish.app", "-c", "android.intent.category.LAUNCHER", "1")
    }
}
tasks.register<RunDebugTask>("runDebug") {
    group = "run"
    description = "installDebug + 启动 dev.termish.app"
    dependsOn(":composeApp:installDebug")
}

/** 卸载后重装：解决设备上旧签名/旧版本冲突（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。 */
abstract class ReinstallDebugTask @Inject constructor() : ExecTask() {
    @TaskAction
    fun reinstall() {
        run("adb", "uninstall", "dev.termish.app", ignoreExit = true)
        run("adb", "install", "-r", "composeApp/build/outputs/apk/debug/composeApp-debug.apk")
        run("adb", "shell", "monkey", "-p", "dev.termish.app", "-c", "android.intent.category.LAUNCHER", "1")
    }
}
tasks.register<ReinstallDebugTask>("reinstallDebug") {
    group = "run"
    description = "卸载 dev.termish.app 后重新安装并启动"
    dependsOn(":composeApp:assembleDebug")
}

/** 校验签名机密就绪（.env 或进程环境变量），供本地 release 前置检查。 */
abstract class CheckSigningSecretsTask @Inject constructor() : DefaultTask() {
    @get:Internal
    abstract val envFilePath: org.gradle.api.provider.Property<String>

    @TaskAction
    fun check() {
        val hasEnv = !System.getenv("ANDROID_KEYSTORE_PASSWORD").isNullOrEmpty()
        val hasFile = java.io.File(envFilePath.get()).isFile
        check(hasEnv || hasFile) {
            "缺少签名机密：请准备项目根 .env（cp .env.example .env 后填值；留底在 ~/Documents/秘钥/）或注入 ANDROID_KEYSTORE_* 环境变量"
        }
        println("✅ 签名机密就绪（jks 缺失时将自动从 ANDROID_KEYSTORE_BASE64 解码）")
    }
}
tasks.register<CheckSigningSecretsTask>("checkSigningSecrets") {
    group = "verification"
    description = "检查 release 签名机密（项目根 .env 或 ANDROID_KEYSTORE_* 环境变量）"
    envFilePath.set(layout.projectDirectory.file(".env").asFile.absolutePath)
}
