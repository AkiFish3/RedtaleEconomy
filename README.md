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

迁移类命令一律先报数（人数 / 条数 / 总额），加 `confirm` 才执行，执行前自动备份到 `<root>/backup/<动作>-<时间戳>/`。

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

本模组在制作过程中使用了 **DeepSeek** 和 **CodeBuddy** 进行 AI 辅助开发。

## 加入我们的服务器

服务器审核QQ群：**1065844449**

## 协议

LGPL-3.0 License
