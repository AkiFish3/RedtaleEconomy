# 更新记录

## 2026-10-20 — R36：玩家数据迁到 `<world>/redtale-data`

**行为变更**

- `storage.json → json.root` 默认值 `%server%/redtale-data` → **`%world%/redtale-data`**：玩家数据（账户/仓库/签到/战令/协会/邮件/委托/排行/流水/快照/写前日志）跟存档走，换存档不再串号；配置仍留 `config/redtale/`。
- 新增占位符 `%world%`（大小写不敏感）。`%server%` 与绝对路径（`D:/redtale-data`）继续可用；显式指定的目录不做任何自动迁移。
- 旧安装零操作升级：开服时若 `json.root` 仍等于旧默认值，自动改写为新默认值，并把旧的 `%server%/redtale-data` **复制**进新目录（只复制、不覆盖、不删除，`<world>/redtale-data/.migration.json` 留来源记录）。

**实现**

- `util/DataRoot`（新）：数据根目录唯一入口 + 两段式时序（加载期落 `redtale-data.pending` → `SERVER_STARTING` 设 `worldRoot` → 合并旧数据 → 重绑消费者）+ 迁移/标记逻辑。
- `util/PathResolver`：支持 `%world%`，新增 `setWorldRoot/worldKnown`；未就绪时不报错、不写服务端根目录。
- `RedtaleModule.rebindDataDir()` + `ModuleManager.rebindDataDirs()`：10 个缓存目录的模块开服后重新解析并重读盘；`Ledger/SnapshotService/TxnJournal/ShopTransactions/RankService/BalanceService/DayRollover` 由 `DataRoot.onRebind` 统一重绑。
- 22 处 `PathResolver.resolveDir(StorageConfig.root(), "%server%/redtale-data")` 全部收敛为 `DataRoot.resolve()`（闸门 `_econ_guard.py` 新增第 6 条防回退）。
- 关服清 `worldRoot`：单人「返回主菜单 → 载入另一个存档」不会沿用上一个存档的数据目录。
- 新增只读诊断 `/rt admin data`（OP4）：一行看解析结果 / 模式 / 存档目录 / 旧目录与 pending 文件数 / 迁移标记。

**测试**

- 新增 `DataRootTest`（11 例）：`%world%` 解析 / 未就绪拒绝 / 穿越拒绝 / 合并不覆盖 / 0 字节占位跳过 / 迁移标记幂等 / 旧默认值识别。

## 2026-10-20 — 仓库初始化

**工程**

- 首次提交：81 个 Java 源文件（其中 12 个测试类）、资源、构建脚本。
- `.gitignore` 补齐：`libs/*.jar`（第三方模组 jar 不入库）、`logs/`、`*.log`。
- `gradle.properties` 移除写死的本机 JDK 路径，改由用户级 `~/.gradle/gradle.properties` 提供，clone 后不再指向别人的盘符。
- `build.gradle`：`libs/ftb-quests.jar`、`libs/ftb-library.jar` 缺失时输出可读警告，而不是抛出一串 import 错误。
- `libs/README.md`：说明两个 jar 的版本与获取方式（ftb-quests 2001.4.13 / ftb-library 2001.2.9）。

**文档与协议**

- README 重写：基本信息表、功能、命令表、构建、鸣谢、加群、协议。
- README.en.md 同步为英文版。
- 协议由 CC0-1.0 改为 **LGPL-3.0**，`fabric.mod.json` 的 `license` 同步，`contact` 补 `sources`。

**分支**

- `1.20.1-fabric` — 当前开发主线（Fabric 1.20.1）。
- `1.21.1-fabric/neoforge` — 预留，迁移起点，见 `docs/MIGRATION-1.21.1.md`。

**功能范围（首版代码包含）**

- 经济：铜本位余额、多档货币展示、多币种钱包（premium / free / voucher FIFO）
- 商店：分页下发、库存、限购、价格护栏、删除进回收站
- 签到：连续签到、循环奖励、补签券
- 每日任务：预算 + 权重求解
- 存储：JSON / MySQL 双后端、流水、快照、双向迁移
- 权限：FTB Ranks → LuckPerms → OP 探测，fail-closed
- 集成：Numismatic、FTB Quests（`redtale_economy:money` 奖励类型）
