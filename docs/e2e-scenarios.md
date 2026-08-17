# 会话/连接场景测试清单（自然语言）

> 执行者：AI agent（模拟器 + adb + logcat 断言）。
> 每个场景含：前置条件、操作步骤、预期行为（含 TermLog 断言模式）。
> 场景通过 = 预期日志出现且 UI 行为正确；报告 PASS/FAIL + 失败原因。

前置环境（所有场景）：
- 模拟器运行中，debug 包已装（`make run`）
- demo 服务器运行（`docker start termish-demo`）：termish@10.0.2.2:2223，密码 termish-demo
- App 主机列表含 demo 主机且已连接过一次（凭据已存 SecretStore）
- 断言基线：操作前 `adb logcat -c` 清空，避免旧日志干扰

---

## A. 连接

### A1 正常连接
- 前置：demo 运行
- 步骤：冷启动 App → 首页点 demo 卡片
- 预期：`I ssh: connect start ... attempt=0 timeout=15000ms` → `I ssh: connected ... kex=... in <1000ms`；
  终端页显示（CTRL 工具栏可见），无错误 banner；span 日志含 ok 与全部 step

### A2 连接拒绝（服务器关闭）
- 前置：docker stop termish-demo（等待端口 2223 确认关闭）
- 步骤：冷启动 → 点卡片
- 预期：`E ssh: connect failed after <10000ms`（快速失败，无 SLOW 特征）；banner 显示失败原因 + 「重新连接」按钮
- 恢复：docker start termish-demo

### A3 黑洞超时（网络悬挂）
- 前置：macOS pf DROP 出站 2223（或等价丢包手段；不可行则标记 SKIP）
- 步骤：点卡片连接
- 预期：`W ssh: connect SLOW/FAIL after >10000ms`（SLOW 特征）；重连场景下 `timeout=5000ms`
- 恢复：恢复 pf 规则

### A4 认证失败（密码错误）
- 前置：主机密码改为错误值
- 步骤：连接
- 预期：`E ssh: connect failed`（认证失败原因）；UI 弹认证框可重输
- 恢复：改回正确密码

### A5 主机密钥变更（TOFU）
- 前置：demo 容器重建（host key 变化）
- 步骤：连接
- 预期：`W ssh: hostkey CHANGED` → 弹核对弹窗；拒绝则连接中止，接受则记录新指纹
- 恢复：删除 App 内已存指纹或重启 demo 保持新 key

## B. 网络故障

### B1 断网（WiFi 关闭）→ 状态正确 + 恢复重连
- 前置：已连接 demo
- 步骤：HOME 退后台 → `svc wifi disable` → 等 5s → 检查状态 → `svc wifi enable` → 回前台
- 预期：`W net: LOST: force close`（TCP 悬挂不再显示绿色）；回前台后 `I ssh: connected`（自动重连）
- 注意：模拟器飞行模式不触发 onLost，用 svc wifi disable

### B2 服务器重启 → 自动重连
- 前置：已连接
- 步骤：`docker restart termish-demo`（快速重启）
- 预期：`W ssh: onClosed status=CONNECTED` → 自动重连 → 第二次 `I ssh: connected`（无需人工）

### B3 重连耗尽 → 后台通知
- 前置：已连接，退后台
- 步骤：`docker stop termish-demo` → 等待
- 预期：`E ssh: reconnect exhausted`（约 3 次尝试 × (5s 超时+退避) ≤ 60s）→
  `I notify: post reconnect_failed` → 系统通知出现（dumpsys 查 termish_session channel，
  color=0xff34d399，含「重新连接」动作）
- 恢复：docker start；点击通知「重新连接」→ 连接恢复

### B4 通知过滤
- 前置：连接后退后台；设置 → 通知 → 关闭「连接断开」事件
- 步骤：停服务器等断开
- 预期：`D notify: skip ... event off`（无通知弹出）
- 恢复：重新开启事件

## C. 会话生命周期

### C1 杀 App 重进 → 会话列表恢复
- 前置：有活跃终端会话 + SFTP 会话（浏览到非 home 目录后退后台）
- 步骤：`am force-stop` → 冷启动
- 预期：`I session: restoreRecent terminals=N sftp=M`；连接页显示断开条目；
  SFTP 进 tab 自动重连并回上次目录（`I sftp: connected`）

### C2 终端断开 banner 手动重连
- 前置：停 demo 后连接失败（banner 显示）
- 步骤：启动 demo → 点 banner「重新连接」
- 预期：连接成功（banner 消失）

### C3 后台保活（前台服务）
- 前置：已连接
- 步骤：HOME 退后台 → 等 30s → 检查
- 预期：前台服务通知常驻；会话保持 CONNECTED（无 onClosed 日志）

## D. Mosh

### D1 Mosh 连接（自动端口）
- 前置：主机连接模式 = Mosh（demo 已装 mosh-server）
- 步骤：连接
- 预期：`I mosh: bootstrap ... mosh-server new -c 256` → `I mosh: mosh-server up port=...` →
  `I mosh: mosh client started` → 终端页显示（状态 CONNECTED）

### D2 Mosh 固定端口被占用
- 前置：主机固定 UDP 端口（如 60100），先手动占住该端口
- 步骤：连接
- 预期：失败提示「固定 UDP 端口仍被旧会话占用」

### D3 Mosh UDP 不通（防火墙）
- 前置：pf DROP UDP 60000-60100
- 步骤：连接
- 预期：SSH 引导成功但 15s 后 `mosh 连接超时`（仍显示连接中 → 超时失败 + 原因提示）

## E. SFTP

### E1 SFTP 断开 → 进 tab 自动重连（同进程）
- 前置：SFTP 已连接，浏览到某目录
- 步骤：停 demo → 操作触发 list 失败 → 启动 demo → 切走再切回 SFTP tab
- 预期：`W sftp: list failed` → banner「连接已断开」→ 自动重连 → `I sftp: connected` → 回原目录

### E2 杀 App → SFTP 恢复 + 路径持久化
- 前置：SFTP 浏览到非 home 目录 → HOME 退后台（触发 persistNow）
- 步骤：`am force-stop` → 冷启动 → 进 SFTP tab
- 预期：恢复条目（session=null）→ 自动重连 → 直接回上次目录（非 home）

---

## 执行纪律

- 每个场景独立报告：PASS / FAIL / SKIP（环境不支持，注明原因）
- 断言失败先确认是应用 bug 还是测试操作问题（看 logcat 全链路 + 状态）
- 发现应用 bug：记录最小复现 + 相关日志，修复后重跑该场景
- 回归触发：连接/会话/通知/网络相关改动后，跑 A+B 全量；C/D/E 按影响面
