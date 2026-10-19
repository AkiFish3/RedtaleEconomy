# 配置项审计（B02）：写死常量清单

> 目标：除了「物理单位换算」和「协议/存储格式常量」，其余可调数值都应该在 `config/redtale/*.json`。
> 本轮审计范围：第 6 轮新增的 10 个模块 + 原有核心。全部结论基于 `grep` + 逐文件阅读。

## 本轮修掉的

| 位置 | 原来 | 现在 |
|---|---|---|
| `BountyCommands` 领取序号上界 | 写死 `1..9` | `GuildConfig.maxBountySlots()`（默认 9，可配） |
| `BountyModule` 位移/在线结算间隔 | 写死 `100` tick | `bounty.json → progressFlushTicks`（20..1200） |
| `BountyModule` 群系采样间隔 | 写死 `40` tick | `bounty.json → biomeSampleTicks`（5..600） |

## 保留的硬编码（有意为之）

| 位置 | 值 | 为什么保留 |
|---|---|---|
| `BountyModule` 传送过滤 | 单 tick 位移 > 64 格不计 | 物理常量：64 格/ tick 远超正常移动上限，配了也没意义 |
| `BountyModule` 在线分钟换算 | 1200 tick = 1 分钟 | 协议常量（20 tick = 1 秒），改它等于改游戏刻 |
| `Logs` 限流窗口 | 60 秒 | 日志规范本身的定义，不是玩法数值 |
| `WarehouseData` 格数对齐 | 9 的倍数 | 受 GUI 每行 9 格约束 |
| `SdmMigrate` 快照文件名时间戳格式 | `yyyyMMdd-HHmmss` | 与 `ConfigManager.timestamp()` 同格式，便于排序 |
| `TerminalRegistry` 方块硬度 | 1.5 | 方块属性，未来可提到 `terminal.json`（目前只有两个方块，收益低） |
| `PassCommands` 等级参数上界 | 1..500 | 防手滑输入；真正的上限由 `battlepass.json → levels.max` 校验 |
| `BoardCommands` 数量参数上界 | 1..2304 | 防手滑；实际由 `board.json → maxCount` 再校验一次 |

## 待办

- 终端方块属性（硬度/抗性/发光）→ 目前写死在 `TerminalRegistry`，等 R17 UI 一起统一下沉到配置。
- 各模块 tick 间隔（`WarehouseModule` 100 tick 兜底、`BoardModule` 600 tick 超时扫描、`MailModule` 重投间隔）里前两个仍是常量，建议下一轮统一成 `<module>.tickInterval`。
