# Redtale Economy（赤物语经济）

为个人服务器 [赤物语](https://space.bilibili.com/3546831095073406) 开发的 Minecraft 经济系统模组。

## 基本信息

| 项目 | 内容 |
|------|------|
| Mod ID | `redtale_economy` |
| MC 版本 | 1.20.1 |
| 平台 | Fabric |
| 依赖 | Fabric API（可选：Numismatic、FTB Quests、FTB Ranks / LuckPerms） |

## 功能

- **经济**：以「铜」为最小单位，多档货币展示；多币种钱包（premium / free / voucher，voucher 按 FIFO 消耗）
- **商店**：分页下发（客户端只拿当前页）、库存、限购、价格上下限与单次调价幅度护栏，删除进回收站
- **签到**：连续签到、循环奖励表、补签券（`makeup_ticket` 物品）
- **每日任务**：预算 + 权重求解，约束不满足时明确拒绝而不是硬凑
- **存储**：`json` / `mysql` 可切，流水（日或月粒度）+ 快照，双向迁移带 dry-run 与整目录备份
- **权限**：FTB Ranks → LuckPerms → OP 自动探测，都探测不到才退回 OP
- **命令别名**：`redirect` 实现，权限仍按目标命令判定
- **仓储**：每人一份个人仓库（默认 54 格，最高 108），原版 9×N 容器界面，任务缴纳可优先扣仓库，`/storage upgrade` 用铜币/赞助点扩容
- **系统悬赏**：每日随机 3 条，9 种行为目标（kill/submit/loot_chest/biome/dimension/craft/fish/travel/playtime），个人进度、完成需领取
- **协会等级**：永久声望 F→S，提供商店折扣、悬赏位加成、称号
- **称号**：写入 StyledChat 的 display_name（`/styledchat set`），可还原；也支持生成 ItemCommander 称号物品
- **战令**：赛季 + 免费/付费双轨，XP 来自签到/任务/在线/消费/FTBQ 完成
- **委托板**：玩家之间发物品类委托，赏金 + 5% 手续费，接单后交付销毁物品
- **邮件**：物品走 Contact 信箱（单一数据源），货币走待领取队列，`/mail claim` 领取
> 数值、间隔、上限、路径、开关、文案全部在 `config/redtale/*.json`，`/redtale reload` 热加载，不需要重启。

软集成（未安装也能正常启动）：Numismatic 作为外部余额源、FTB Quests 注册 `redtale_economy:money` 奖励类型。

## 命令

| 命令 | 权限节点 | 说明 |
|------|----------|------|
| `/redtale help` | `redtale.use` | 命令索引 |
| `/redtale info` / `modules` | `redtale.use` | 运行时状态与模块开关 |
| `/redtale reload` / `save` | `redtale.admin` | 热重载 / 落盘（含备份） |
| `/redtale edit <file>` | `redtale.admin` | 游戏内改标量配置 |
| `/redtale lang <code>` | `redtale.admin` | 切换语言 |
| `/redtale migrate provider\|storage\|identity ... [confirm]` | `redtale.owner` | 切余额源 / 切存储后端 / 接回旧 UUID 账号 |
| `/quests` | `redtale.use` | 转发 FTB Quests（未装则转发原版命令） |
| `/storage [页]` · `/storage upgrade` · `info` | `redtale.use` | 个人仓库 / 扩容 / 详情（别名 `/warehouse` 可配） |
| `/bounty` · `claim <序号>` · `claimall` | `redtale.use` | 系统悬赏 |
| `/guild` · `/guild add <玩家> <声望>` | `use` / `admin` | 协会等级与特权 |
| `/title` · `list` · `set|clear|give` | `use` / `admin` | 称号 |
| `/pass` · `claim <级> [premium]` · `unlock` · `addxp` | `use` / `admin` | 战令 |
| `/board` · `post|accept|deliver|cancel` | `redtale.use` | 玩家委托板 |
| `/mail` · `/mail claim` · `give|money` | `use` / `admin` | 邮件与待领取货币 |
| `/redtale grant money|item <玩家> ...` | `redtale.admin` | 补发奖励（走邮件，不直接改余额） |

迁移类命令一律先报数（人数 / 条数 / 总额），加 `confirm` 才执行，执行前自动备份到 `<root>/backup/<动作>-<时间戳>/`。

## 配置文件

全部在 `config/redtale/`，字段说明由 schema 自动生成到同目录 `README.md`（改配置不会改那份说明）。

| 文件 | 作用 | 主要字段 |
|------|------|----------|
| `core.json` | 总开关 / 权限节点 / 语言 / 备份份数 | `language`、`permissions.*`、`backup.maxBackups`、`confirm.dangerousOps` |
| `economy.json` | 币值与转账 | 面额、转账上下限、手续费 |
| `storage.json` | 存储后端 | `backend`、`json.root`、`ledger.*`、`snapshots*`、`mysql.*` |
| `premium.json` | 赞助货币 | `enabled`、`whitelist` |
| `ftbq.json` | FTBQ 奖励类型 | `enabled`、`registerReward`、`entryCommand` |
| `checkin.json` | 签到 | `resetHour`、`loop`、`breakPolicy`、`tiers[]`、`makeup.*` |
| `tasks.json` | 每日任务参数 | `tasksPerDay`、`refreshPerDay`、`resetHour`、`allowWeightFlex`、`defaultBudget`、`defaultKinds`、`defaultReward.money`、`refreshTicket.{item,fromWarehouse}` |
| `tasks/pools/*.json` | 任务物品池（每个池一个文件，也兼容旧 `tasks/pools.json`） | 池 id → `{items:[{id,weight}], budget, kinds}` |
| `tasks/definitions/*.json` | 任务模板 | `pool`、`kinds`、`budget`、`reward.money`、`completion` |
| `shops/*.json` | 商店数据 | 每个商店一个文件 |
| `warehouse.json` | 个人仓库 | `slots`、`maxSlots`、`rows`、`blockItem`、`taskSubmitFromWarehouse`、`upgrade.steps[]` |
| `bounty.json` | 系统悬赏 | `resetHour`、`slotCount`、`pool[]`（type/target/count/unique/weight/reward） |
| `guild.json` | 协会等级 | `ranks[]`、`rep.*`、`perks.*`、`titles[]` |
| `title.json` | 称号 | `provider`、`commandTemplate`、`displayNameTemplate`、`restoreTemplate`、`item.*` |
| `battlepass.json` | 战令 | `season`、`levels.*`、`xp.*`（含 `xp.ftbq`）、`unlock.*`、`tracks.free/premium[]` |
| `board.json` | 委托板 | `feePercent`、`expireHours`、`maxOpenPerPlayer`、`minBounty`、`maxBounty`、`blockedItems` |
| `mail.json` | 邮件 | `senderName`、`maxQueueMoney`、`retryIntervalSeconds`、`openCommand` |
| `sdm_migrate.json` | SDM 余额迁移 | `enabled`、`sourceCurrencyId`、`ratio`、`ledgerRef`、`batchSize` |
| `modules.json` | 模块开关 | 每个模块一个布尔 |

## 界面（数据驱动）

所有新界面由 `config/redtale/ui/**.json5` 描述，改布局不用重编译。
首次启动会把内置布局释放到该目录（不覆盖你改过的文件）；以 `_` 开头的文件/目录视为禁用。

```
config/redtale/ui/
├─ menu/main.json5     主入口（按 K 打开）
├─ theme.json5         配色（background/content/border/textDark/textLight/accent/danger）
└─ <你新建的>.json5    文件名 = 布局 id，如 tasks/screen.json5 ⇒ tasks/screen
```

控件类型：`label` / `button` / `list` / `grid` / `slot-grid` / `progress` / `image` / `tab`。
`type` 写错不会崩，只会不渲染（服务端日志会提示解析失败的文件）。

动作（`action` 字段）：`cmd:<命令>` 以玩家身份执行；`ui:<布局id>` 切布局；`close` 关闭。
`list`/`grid` 的每一行可以用 `标签|参数` 形式，节点的 `action` 里写 `%s` 接收该参数
（内置主面板就是这样把每个入口变成可点按钮的）。

| 命令 | 作用 |
|---|---|
| `/rtui <布局id>` | 打开某份布局（调试 / 服主自测） |
| `/rtlayout <布局id>` | 拖拽编辑器：拖动移动、右下角缩放、方向键微调、`S` 保存（先备份再原子写） |
| `/rtedit <商店id>` | 商店表格编辑器：搜索、点表头排序、勾选多行批量改价、取价、上架 |

> 编辑器只改 JSON，不动服务端校验：改价/上架仍然走 `/shop admin ...`，权限与价格上下限照旧生效。

## 构建

需要 **JDK 17**（Gradle 8.3 / Loom 1.3.7 不支持 JDK 21+）。JDK 路径不要写进仓库，放用户级配置：

```properties
# ~/.gradle/gradle.properties
org.gradle.java.home=/path/to/jdk-17
```

第三方模组 jar 不入库（版权原因），按 [`libs/README.md`](libs/README.md) 放好再构建：

```bash
./gradlew build
# 产物：build/libs/redtale_economy-<version>.jar
```

## 鸣谢

本模组在制作过程中使用了 **DeepSeek-V4.1-Flash-1017** 与 **WorkBuddy** 进行 AI 辅助开发。

## 加入我们的服务器

服务器审核QQ群：**1065844449**

## 协议

LGPL-3.0 License
