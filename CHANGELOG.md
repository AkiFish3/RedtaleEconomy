# 更新记录

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
