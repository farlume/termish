# e2e 场景测试

模拟器黑盒回归：adb 驱动 + TermLog 打点断言，覆盖连接/断网/重连/通知等
系统级场景（Kotlin 测试测不到的进程外故障注入）。

## 前置条件

1. **模拟器已启动**（`adb devices` 可见），debug 包已安装（`make run`）
2. **demo 服务器**在运行（宿主机 Docker）：`docker start termish-demo`
   - `termish@127.0.0.1:2223`，密码 `termish-demo`；模拟器经 `10.0.2.2:2223` 访问
3. **App 主机列表已配置 demo 主机**并成功连接过一次：
   - 主机名 `10.0.2.2`，端口 `2223`，用户名 `termish`，密码 `termish-demo`
   - 首次连接成功会保存凭据到 SecretStore（脚本依赖，否则连接弹认证框卡住）
   - 缺失时脚本会明确报错并提示（`ensure_demo_host`）

## 运行

```bash
./scripts/e2e/e2e-run.sh          # 全部 case
./scripts/e2e/e2e-run.sh 004       # 只跑 004（前缀匹配）
./scripts/e2e/e2e-run.sh -v        # 详细输出
```

结果汇总：PASS/FAIL 计数 + 失败列表；任一失败退出码 1。

## 断言机制

- 每个 case = adb 操作序列 + **logcat 断言**（TermLog/TermTrace 打点是断言源）
- `log_mark` 记录日志时间基线 → `wait_log` 只匹配基线之后的新日志
  （连接可能 0.5s 完成，断言不认旧日志；多模式断言互不干扰）
- 断言超时（默认 15s，可传参）后失败退出

## 当前 case

| case | 场景 | 断言 |
|------|------|------|
| 001 | 正常连接 | connected + span 阶段耗时 |
| 002 | 连接拒绝（demo 停止） | 快速失败（<10s 无 SLOW 特征） |
| 003 | 黑洞超时（pf DROP） | SLOW/FAIL >10s（**需 macOS sudo -n，不可用时自动 skip**） |
| 004 | 断网 LOST → 主动断开 → 恢复重连 | LOST force close + 重连 |
| 005 | 服务器重启（docker restart） | 断开检测 + 自动重连成功 |
| 006 | 重连耗尽 → 后台通知 | reconnect exhausted + 通知出现 |

## 新增 case 模板

```bash
#!/usr/bin/env bash
# NNN 场景描述
source "$(dirname "$0")/../lib.sh"

launch_app
open_demo_terminal
wait_connected 25
# 操作...
log_mark
<操作>
assert_log "<TermLog 模式>" "断言描述" 20
```

注意：`set -e` 不要加（断言失败需走 else 分支）；case 最后一条命令的
退出码即 case 结果（失败路径记得 `exit 1`）。

## 环境限制

- 模拟器飞行模式不触发 onLost（虚拟网卡直接消失）——断网场景用
  `svc wifi disable`（真机行为正常）
- 黑洞超时依赖 macOS pf（免密 sudo）；无权限时 case 自动 skip（不算失败）
