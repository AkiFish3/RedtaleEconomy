# Redtale Economy

赤物语（Redtale）服务器的经济系统模组。Fabric 1.20.1 / Java 17。

数值、间隔、上限、路径、开关、文案全部在 `config/redtale/*.json` 里，`/redtale reload` 热加载，
改完不需要重启。代码里只保留默认值。

## 功能

| 模块 | 内容 |
|---|---|
| economy | 余额本体，以「铜」为最小单位，多档货币展示；多币种钱包（premium / free / voucher，voucher 按 FIFO 消耗） |
| shop | 分页商店（客户端只拿当前页）、库存、限购、价格上下限与单次调价幅度护栏、删除走回收站 |
| checkin | 连续签到、循环奖励表、补签券（`makeup_ticket` 物品） |
| tasks | 每日任务（预算 + 权重求解，约束不满足时明确拒绝而不是硬凑） |
| storage | 后端可切 `json` / `mysql`；流水（日或月粒度）、快照、双向迁移（dry-run + 整目录备份） |
| premium | 高级货币的发放与消费 |
| aliases | 命令别名（`redirect` 实现，权限仍按目标命令判定） |

软集成，未装也能正常启动：

- **Numismatic** — 作为外部余额源（`/redtale migrate provider numismatic`）
- **FTB Quests** — 注册 `redtale_economy:money` 奖励类型
- **FTB Ranks / LuckPerms** — 权限提供方，都探测不到才退回 OP

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

## 命令

| 命令 | 权限节点 | 说明 |
|---|---|---|
| `/redtale help` | `redtale.use` | 命令索引 |
| `/redtale info` / `modules` | `redtale.use` | 运行时状态与模块开关 |
| `/redtale reload` / `save` | `redtale.admin` | 热重载 / 落盘（含备份） |
| `/redtale edit <file>` | `redtale.admin` | 游戏内改标量配置 |
| `/redtale lang <code>` | `redtale.admin` | 切换语言 |
| `/redtale migrate provider <internal\|numismatic> [confirm]` | `redtale.owner` | 切余额来源 |
| `/redtale migrate storage <json\|mysql> [confirm]` | `redtale.owner` | 切存储后端 |
| `/redtale migrate identity <from> <to> [confirm]` | `redtale.owner` | 离线↔正版 UUID 变化后接回旧账号 |
| `/quests` | `redtale.use` | 转发 FTB Quests（未装则转发原版命令） |
| `/rt` | — | `/redtale` 的短别名 |

迁移类命令一律先报数（人数 / 条数 / 总额），加 `confirm` 才执行，执行前自动备份到
`<root>/backup/<动作>-<时间戳>/`。

## 配置

`config/redtale/` 按模块分文件：

```
core.json  economy.json  shop/settings.json  checkin.json  premium.json
storage.json  ftbq.json  sdm_migrate.json  commands.json  modules.json
```

路径支持绝对路径与 `%server%` 变量。后端为 `mysql` 时，密码可以放在单独文件（`mysql.passwordFile`）里，
不进版本库；外部 JDBC 驱动也走配置（`mysql.driverJar` / `mysql.driverClass`），不需要在编译期引入。

## 测试

```bash
./gradlew test
```

纯逻辑部分（币值解析、任务求解、路径解析、数据版本兼容、身份别名链、配置迁移、MySQL 语句构造）都有单测，
不依赖游戏运行时。

## 许可

CC0 1.0 Universal，见 [LICENSE](LICENSE)。
