---
name: ai-adb-run
description: "通过 adb 执行 .aiadb-test/cases/ 下的自然语言 Android UI 测试用例（模拟器 + uiautomator dump + logcat 断言）。当用户说 ai-adb-run、'跑测试'、'执行用例'、'跑 A 组/某编号'、'回归测试' 时使用。支持 run 全部 / run <组字母> / run <编号>；执行前环境自检（模拟器/包/网络/日志基线），不可修复项缺失报错停止；FAIL 存证截图+日志到 .aiadb-test/results/ 并写 summary。反触发：仅适用于 Android 项目；无 cases/ 目录时报错提示先跑 ai-adb-gen。"
---

# ai-adb-run（AI 执行 Android UI 测试用例）

> 通用技能：AI 通过 adb 对 Android 应用做黑盒 UI 自动化测试——**用例执行**。
> 不绑定任何项目——用例由 `ai-adb-gen` 产出，存放在项目根 `.aiadb-test/`。
> 部署：项目级 `.agents/skills/`（跟随仓库、克隆即得；不再安装用户级副本）。

## 适用范围与拒绝规则（容错机制）

本技能**仅适用于 Android 项目**（含 KMP / Compose Multiplatform 的 android target）。

执行前必须做**项目类型检查**（缺一即拒绝）：

| 检查 | 通过条件 |
|------|----------|
| AndroidManifest | 项目内存在 `AndroidManifest.xml`（任意模块） |
| Android 构建 | `build.gradle(.kts)` 有 `android` block / `applicationId`，
  或 KMP 的 `androidTarget()` / `androidLibrary()` |

**拒绝规则**：
- 任一检查不通过 → 输出 `❌ ai-adb-run 仅适用于 Android 项目` + 检测结果，
  **不执行**任何命令
- 混合项目（含 android target 的多平台）→ 允许，但只执行 Android 侧用例；
  非 Android 平台场景标注 SKIP
- 纯 iOS / 桌面 / Web / 服务端 / 无 UI 项目 → 拒绝（adb 黑盒 UI 测试
  对它们无意义；如需其他测试方式，由用户另行指定）
- 拒绝后不猜测、不硬跑、不降级为别的测试方式——明确告知用户

## 用例输入

- 目录：`.aiadb-test/cases/*.md`（ai-adb-gen 产出）
- 格式：每个用例 `### <组字母><序号> <场景名>`，字段：前置/步骤/预期/恢复/依赖
  （模板见 ai-adb-gen）
- **无 cases/ 目录或为空 → 报错停止，提示先跑 ai-adb-gen**，不硬跑

## 环境自检（run 前必做）

| 项 | 检查 | 缺失处理 |
|----|------|----------|
| **项目类型** | 见「适用范围与拒绝规则」（manifest/构建配置） | ❌ **拒绝执行**，不猜测不硬跑 |
| 模拟器/设备 | `adb devices` | 自动拉起（可配置 AVD 名）或报错 |
| 目标包已装 | `pm list packages` | 按项目约定安装（gradle/脚本） |
| 网络 | `dumpsys connectivity` | 自动恢复 |
| 项目前置 | 见 cases/*.md 头部「前置环境（所有场景）」自检表；
  无则按各用例「前置」字段确认 | 可自动修复则修复；否则报错停止并提示步骤 |
| 日志基线 | `logcat -c` | — |

规则：**不可修复项缺失 = 停止执行**，不跳过不硬跑（跳过会导致后续用例全部误判失败）。

## run 执行流程

1. **环境自检**（见上）——不可修复项缺失 = 报错停止
2. 筛选：`run`（全部）/ `run <组>`（如 `run A`）/ `run <编号>`（如 `run A1`）
3. 逐用例执行；**依赖未满足（前置用例 SKIP/FAIL）→ 本用例 SKIP**（注明原因）
4. **偶发失败重试**：非环境类失败自动重试 1 次；重试仍失败才算 FAIL
5. **FAIL 存证**（必做）：
   - `screencap` 截图 → `.aiadb-test/results/<时间戳>/<编号>.png`
   - `logcat -d` 相关 tag 段 → 同目录 `<编号>.log`
   - 报告附存证路径
6. **结果持久化**：写 `.aiadb-test/results/<时间戳>/summary.md`
   （PASS/FAIL/SKIP 清单 + 存证索引 + 发现的 bug）
7. 发现 bug：记录最小复现 + 日志，修复后重跑该用例
8. 报告格式：
   ```
   [组] 用例名         ✅ PASS / ❌ FAIL / ⏭️ SKIP
   失败/SKIP 原因
   存证: results/<时间戳>/<编号>.png
   ```

## adb 操作技巧（执行必读，血泪教训）

1. **键盘弹出 = 坐标全部失效**：聚焦输入框弹键盘 → 页面滚动 → 旧 dump 坐标作废。
   规则：每步操作前重新 `uiautomator dump` 取实时坐标，操作后 dump 确认，再取下一步
2. **清空输入框**：`input keycombination 113 29`（CTRL+A 全选）+ `input keyevent 67`（删除）——
   不要数退格（Compose 字段有默认值时退格数错就拼接）
3. **键盘遮挡字段**：`input keyevent 111`（ESC）收键盘 → dump 找真实位置 → 聚焦 → 输入 → ESC → dump 确认
4. **密码框验证**：password 字段 dump 显示 `••••`，用 EditText 节点数点确认长度，不要数文本
5. **字段串扰**：tap 落错字段（键盘遮挡常见）→ 输入进别的字段。每步输入后 dump 确认落在哪
6. **顶部按钮**（保存/返回）坐标随滚动变，每次 dump 取实时位置
7. **input text 特殊字符**：`.` 等用反斜杠转义（`10\.0\.2\.2`）
8. **慢操作等待**：连接/保存后 sleep 2-3s 再 dump（动画/异步）
9. **退出 UI 前必须确认操作完成**：点击开关/按钮后先 dump 确认生效（开关已切换/值已保存），
   再返回退出——否则操作实际没生效，后续断言全错
10. **配置类操作后验证持久化**：`run-as <pkg> cat shared_prefs/...` 确认写入结果
11. **文本输入优先**：能 `input text` 就别模拟逐键；字段有默认值先 CTRL+A 清空

## 断言机制建议

- 首选**应用日志**（结构化打点 tag/级别）——最精确；无日志应用降级到 UI 树/截图
- 次选 UI 树（`uiautomator dump` 文本/状态/坐标）
- 补充：`dumpsys notification`（通知）、`dumpsys connectivity`（网络）、截图对比
- 断言要匹配"操作产生的新日志"：操作前记录基线（`logcat -c` 或时间戳），
  避免旧日志误判（尤其快速完成的操作，如连接成功仅需几百 ms）
