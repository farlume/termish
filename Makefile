# Termish 本地工作流入口（薄壳）
#
# 所有流程知识都在 Gradle tasks（根 build.gradle.kts）里，这里只做快捷别名；
# 直接 ./gradlew <task> 等价可用，CI 亦复用同一组任务。
#
# 变量注入：自动加载项目根 .env（gitignore）。注意 gradle daemon 不继承
# 新环境变量，改动 secrets/.env 后执行 make gradle-stop。

-include .env
export

GRADLEW := ./gradlew

.DEFAULT_GOAL := help

.PHONY: help
help: ## 显示本帮助
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

## ---------- 日常开发（对应 gradle task） ----------

.PHONY: debug
debug: ## 构建 debug APK
	$(GRADLEW) :composeApp:assembleDebug

.PHONY: run
run: ## 构建 + 安装 + 启动（gradle runDebug）
	$(GRADLEW) runDebug

.PHONY: reinstall
reinstall: ## 卸载后重装（gradle reinstallDebug，治签名冲突）
	$(GRADLEW) reinstallDebug

## ---------- 测试 ----------

.PHONY: test
test: ## 单元测试 + 集成测试（sshd/mosh 不在时自动 SKIP）
	$(GRADLEW) :composeApp:desktopTest

.PHONY: test-integration
test-integration: ## 集成测试：自动起 sshd 后跑（gradle testIntegration）
	$(GRADLEW) testIntegration

.PHONY: lint lint-kt
lint: ## Android lint
	$(GRADLEW) :composeApp:lintRelease

lint-kt: ## ktlint 检查（规则读 .editorconfig；ktlintFormat 可自动修）
	$(GRADLEW) ktlintCheck

## ---------- 发版 ----------

.PHONY: release
release: ## 构建已签名 release APK + AAB（先校验签名机密）
	$(GRADLEW) checkSigningSecrets :composeApp:assembleRelease :composeApp:bundleRelease
	@echo "产物: composeApp/build/outputs/{apk,bundle}/release/"

.PHONY: bump
bump: ## 三平台版本号联动，用法: make bump V=1.0.1（预览: DRY=1）
	./scripts/bump-version.sh $(if $(DRY),--dry-run,) $(V)

## ---------- iOS（一次性原生依赖） ----------

.PHONY: ios-native
ios-native: ## 交叉编译 OpenSSL + libssh2 → iosApp/native/
	./scripts/build-ios-native.sh

.PHONY: ios-framework
ios-framework: ## 编译 Kotlin framework（模拟器 + 真机 debug）
	$(GRADLEW) :composeApp:linkDebugFrameworkIosSimulatorArm64 \
	           :composeApp:linkDebugFrameworkIosArm64

## ---------- 维护 ----------

.PHONY: gradle-stop
gradle-stop: ## 停掉 gradle daemon（改了环境变量后必须执行）
	$(GRADLEW) --stop

.PHONY: clean
clean: ## 清理构建产物
	$(GRADLEW) clean

.PHONY: devices
devices: ## 列出已连接设备/模拟器
	adb devices -l
