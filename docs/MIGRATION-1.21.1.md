# 1.21.1 迁移（Fabric → Fabric/NeoForge 双端）

分支：`1.21.1-fabric/neoforge`（本文件所在分支）
基线：`1.20.1-fabric`

> 状态：**预研，未开工**。下面的清单是对着现有代码逐项过出来的，标「待验证」的条目需要在
> 真实依赖到位后跑一次确认，不是定论。

## 目标

| 项 | 1.20.1（现状） | 1.21.1（目标） |
|---|---|---|
| JDK | 17 | 21 |
| Gradle | 8.3 | 8.8+ |
| Loom | 1.3.7 | 1.7+ |
| Yarn | 1.20.1+build.10 | 1.21.1+build.x |
| Fabric API | 0.92.6+1.20.1 | 0.116.x+1.21.1 |
| 平台 | Fabric | Fabric + NeoForge（Architectury） |
| FTB Quests | 2001.x | 2101.x（待验证） |

## 工作量分级

| 级别 | 内容 | 影响面 |
|---|---|---|
| 高 | 网络层重写（`CustomPayload` + `PayloadTypeRegistry`） | `net/ShopNetworking`、`net/ConfigNetworking`、`net/ShopProtocol`、`net/ConfigProtocol`、`client/ShopClientNetworking`、`client/config/ConfigClientNetworking` |
| 高 | 平台抽象（Fabric API → Architectury，common/fabric/neoforge 三模块） | 全部 `module/`、`command/`、`RedtaleEconomy`、`RedtaleEconomyClient` |
| 中 | `new Identifier(ns, path)` → `Identifier.of(ns, path)` | 物品注册、包 id 定义、FTBQ 奖励类型 id |
| 中 | FTBQ API 版本跨度（`Reward` / `RewardTypes.register` / NBT 读写） | `integration/MoneyReward`、`integration/FtbqIntegration` |
| 低 | 物品组件化（1.20.5+ NBT→DataComponent） | 只有 `makeup_ticket` 一个物品，暂未用自定义 NBT |
| 低 | 命令树（Brigadier 小版本升级） | `command/*` |
| 低 | 反射桥接重测（Numismatic / FTB Ranks / LuckPerms 的 1.21.1 版本类名与方法） | `economy/NumismaticBridge`、`perm/FtbRanksProvider`、`perm/LuckPermsProvider` |

## common 层抽取清单

基线实测：81 个 Java 源文件里，**只有 14 个直接 `import net.fabricmc.*`**，其余 67 个只依赖
`net.minecraft.*`（Architectury 的 common 模块用 Mojmap 即可直接编译）。

### 必须下沉到平台层（14 个）

| 文件 | 现在依赖的 Fabric API | 平台层怎么给 |
|---|---|---|
| `RedtaleEconomy` | `ModInitializer` | Fabric/NeoForge 各一个入口，调 `CommonBootstrap.init()` |
| `RedtaleEconomyClient` | `ClientModInitializer` | 同上，`ClientBootstrap.init()` |
| `net/ShopNetworking` | `ServerPlayNetworking` | `INetworkPlatform.sendToPlayer(payload)` |
| `net/ConfigNetworking` | `ServerPlayNetworking` | 同上 |
| `client/ShopClientNetworking` | `ClientPlayNetworking` | `INetworkPlatform.sendToServer(payload)` |
| `client/config/ConfigClientNetworking` | `ClientPlayNetworking` | 同上 |
| `config/ConfigManager` | `FabricLoader.getConfigDir()` | `IPlatformPaths.configDir()` |
| `util/PathResolver` | `FabricLoader.getGameDir()` | `IPlatformPaths.gameDir()` |
| `lang/Lang` | 资源读取 | `IPlatformPaths` + 平台资源管理器 |
| `perm/Permissions` | `FabricLoader.isModLoaded` | `IPlatform.isModLoaded(id)` |
| `economy/NumismaticBridge` | `FabricLoader.isModLoaded` | 同上 |
| `integration/FtbqIntegration` | `FabricLoader.isModLoaded` | 同上 + 各自的 `RegisterEvent` |
| `module/impl/StorageModule` | 生命周期事件 | `IServerLifecycle.onStart/onStop` |
| `command/QuestsCommand` | 命令注册回调 | `ICommandPlatform.register(node)` |

### 可留在 common（67 个）

`config/`（除 ConfigManager 外 11 个）、`storage/`（5 个）、`shop/`（3 个）、`economy/`（除 Bridge 外 4 个）、
`util/`（除 PathResolver 外 2 个）、`module/` 的接口与大部分 impl、`net/` 的协议定义（`ShopProtocol`、
`ConfigProtocol`、`PacketIds`）、`command/` 里只接 Brigadier + `ServerCommandSource` 的部分。

> 这些文件在 1.21.1 下只需改映射（Yarn→Mojmap）与少量 API 签名，不需要架构改动。

### 平台抽象接口（建议先写这 5 个）

```
IPlatform           boolean isModLoaded(String id); String loaderName();
IPlatformPaths      Path gameDir(); Path configDir();
INetworkPlatform    void sendToPlayer(ServerPlayerEntity, CustomPayload); void sendToServer(CustomPayload);
IServerLifecycle    void onServerStarted(MinecraftServer); void onServerStopping(MinecraftServer);
ICommandPlatform    void register(LiteralArgumentBuilder<ServerCommandSource>);
```

这 5 个接口 + 两个平台的实现（约 10 个类）就是双端化的全部新增面，其余是机械替换。

## 迁移顺序

1. **先跑通编译空壳**：Loom/Gradle/JDK 升级 + `gradle.properties` 版本号替换，只让 `build` 过，不修业务。
2. **Identifier 批量替换**：`new Identifier(` → `Identifier.of(`，再全量 `./gradlew build` 收口。
3. **网络层**：自定义包改成 `CustomPayload` record + `PayloadTypeRegistry`，先只做 shop 一个包验证链路，再推 config。
4. **平台抽象**：照 `redtale-fabric-neoforge-template` 的 common/fabric/neoforge 结构拆模块，14 个 `net.fabricmc` 文件全部下沉。
5. **集成层**：换 FTBQ 2101.x 重新编译 `MoneyReward`，验证 `claim()` 签名；Numismatic / 权限反射逐项重测。
6. **数据兼容**：`DataCompat.ACCOUNT_VERSION` 保持 1（余额结构未变），`IdentityMap` 无需改；MySQL 表结构不变。
7. **真机冒烟**：`runServer` 起服 + `/redtale modules` + 一次购买 + 一次签到。

## 已知会编译失败的锚点（先 grep 这些）

基线实测：`new Identifier(` **3 处**；`PacketByteBuf` **7 个文件**；`NbtCompound` **1 个文件**；
`ServerPlayNetworking` / `ClientPlayNetworking` **4 个文件**（`net/ShopNetworking`、`net/ConfigNetworking`、
`client/ShopClientNetworking`、`client/config/ConfigClientNetworking`）。

```bash
grep -rn "new Identifier(" src/main/java
grep -rn "ServerPlayNetworking\|ClientPlayNetworking\|PacketByteBuf" src/main/java
grep -rn "NbtCompound" src/main/java
```

## 验收标准

- [ ] `./gradlew build` 在 JDK 21 下通过，产物可加载
- [ ] `runServer` 启动 0 ERROR，`/redtale modules` 正常
- [ ] 商店分页拉取 / 购买 / 退款链路通
- [ ] 配置热重载（`/redtale reload`）生效
- [ ] FTBQ 奖励类型在任务编辑器里可见且能领取
- [ ] 1.20.1 的账号数据能原样被 1.21.1 读取（`_version` 不升）
