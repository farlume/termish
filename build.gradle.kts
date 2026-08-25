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
// ktlint：mavenCentral 的 CLI jar（JavaExec），不依赖插件门户（网络不稳）；
// 规则读 .editorconfig，检查 composeApp 全部 Kotlin 源集
// ---------------------------------------------------------------------------
val ktlintVersion = "1.5.0"
val ktlintConfig = configurations.create("ktlint") {
    isCanBeConsumed = false
    isCanBeResolved = true
    // ktlint-cli 发布 external/shadowed 两个 variant：选 shadowed（fat jar，
    // 含全部依赖，JavaExec 可直接运行）
    attributes {
        attribute(Attribute.of("org.gradle.dependency.bundling", String::class.java), "shadowed")
    }
}
dependencies {
    ktlintConfig("com.pinterest.ktlint:ktlint-cli:$ktlintVersion")
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

/** 构建随 App 分发的标准库 Python Agent Bridge zipapp。 */
val agentBridgeBuild = tasks.register<ExecTask>("agentBridgeBuild") {
    group = "build"
    description = "构建 composeResources/files/termish-agent.pyz"
    inputs.dir(layout.projectDirectory.dir("agentBridge/termish_agent"))
    inputs.file(layout.projectDirectory.file("agentBridge/build.py"))
    outputs.file(layout.projectDirectory.file("composeApp/src/commonMain/composeResources/files/termish-agent.pyz"))
    doLast {
        run("python3", "agentBridge/build.py")
    }
}

// Bridge 产物是 commonMain Compose 资源：所有平台复制资源前先生成，既消除
// Gradle 隐式依赖告警，也保证 APK/framework/桌面包携带的 pyz 与源码一致。
project(":composeApp").tasks.configureEach {
    if (name.startsWith("copyNonXmlValueResourcesFor")) dependsOn(agentBridgeBuild)
}

/** Agent Bridge 协议/Adapter 纯 Python 单测（无第三方依赖）。 */
tasks.register<ExecTask>("agentBridgeTest") {
    group = "verification"
    description = "运行 Agent Bridge Python 单元测试"
    doLast {
        run("python3", "-m", "unittest", "discover", "-s", "agentBridge/tests", "-v")
    }
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

    @get:Internal
    abstract val rootPath: org.gradle.api.provider.Property<String>

    @TaskAction
    fun check() {
        val root = java.io.File(rootPath.get())
        val fileEnv = mutableMapOf<String, String>()
        java.io.File(envFilePath.get()).takeIf { it.isFile }?.readLines()?.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && '=' in trimmed) {
                fileEnv[trimmed.substringBefore('=').trim()] = trimmed.substringAfter('=').trim()
            }
        }
        val legacy = java.util.Properties().apply {
            root.resolve("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
        }
        fun secret(envKey: String, legacyKey: String): String? =
            System.getenv(envKey)?.takeIf { it.isNotBlank() }
                ?: fileEnv[envKey]?.takeIf { it.isNotBlank() }
                ?: legacy.getProperty(legacyKey)?.takeIf { it.isNotBlank() }

        val configuredPath = secret("ANDROID_KEYSTORE_FILE", "storeFile")
        val keyFiles =
            buildList {
                if (configuredPath != null) {
                    val configured = java.io.File(configuredPath)
                    add(if (configured.isAbsolute) configured else root.resolve(configuredPath))
                    add(root.resolve("composeApp").resolve(configuredPath))
                }
                add(root.resolve("termish-release.jks"))
            }
        val hasKeyMaterial = keyFiles.any { it.isFile } || secret("ANDROID_KEYSTORE_BASE64", "keystoreBase64") != null
        val missing =
            buildList {
                if (!hasKeyMaterial) add("ANDROID_KEYSTORE_FILE 或 ANDROID_KEYSTORE_BASE64")
                if (secret("ANDROID_KEYSTORE_PASSWORD", "storePassword") == null) add("ANDROID_KEYSTORE_PASSWORD")
                if (secret("ANDROID_KEY_ALIAS", "keyAlias") == null) add("ANDROID_KEY_ALIAS")
                if (secret("ANDROID_KEY_PASSWORD", "keyPassword") == null) add("ANDROID_KEY_PASSWORD")
            }
        check(missing.isEmpty()) {
            "release 签名配置不完整，缺少：${missing.joinToString()}。请补齐项目根 .env 或 CI Secrets"
        }
        logger.lifecycle("release 签名机密已完整配置")
    }
}
tasks.register<CheckSigningSecretsTask>("checkSigningSecrets") {
    group = "verification"
    description = "检查 release 签名机密（项目根 .env 或 ANDROID_KEYSTORE_* 环境变量）"
    envFilePath.set(layout.projectDirectory.file(".env").asFile.absolutePath)
    rootPath.set(layout.projectDirectory.asFile.absolutePath)
}

/** ktlint 检查/格式化（规则读 .editorconfig，覆盖 composeApp 全部 Kotlin 源集）。 */
fun ktlintTask(name: String, group: String, format: Boolean) =
    tasks.register<JavaExec>(name) {
        this.group = group
        classpath = ktlintConfig
        mainClass.set("com.pinterest.ktlint.Main")
        if (format) args("--format")
        args("--relative", "composeApp/src")
        // configuration cache：参数固定，无需额外配置
        outputs.upToDateWhen { false }
    }

ktlintTask("ktlintCheck", "verification", format = false)
ktlintTask("ktlintFormat", "formatting", format = true)
