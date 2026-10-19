# 1.21.1 迁移预研（B20）

> 现状：1.20.1 Fabric 单体，93 个 Java 文件，其中 **33 个**直接 `import net.fabricmc.*`
> （事件、FabricLoader、网络、BlockEntityType 工厂、客户端 KeyBinding）。
> 目标形态：`common / fabric / neoforge` 三模块，1.21.1 + NeoForge 21.1.215。

## 1 拆分判断（按依赖分类）

| 类别 | 数量 | 处理 |
|---|---|---|
| 纯逻辑（Currency、Ledger、PathResolver、Checkin 天数、权重求解…） | 60 个左右 | 直接进 `common`，零改动 |
| 只依赖 Minecraft 类（方块/实体/菜单） | 与上重叠 | `common` + 版本适配层 |
| 依赖 Fabric API（事件/网络/加载器） | **33** | 拆成 `common` 抽象接口 + 两端实现 |

`net.fabricmc` 出现在这些位置（grep 实测）：

- `client/*`：`ClientTickEvents`、`KeyBindingHelper`、`ClientPlayNetworking`
- `net/*`：`ServerPlayNetworking`、`PacketByteBuf`
- `integration/*`：`FabricLoader.isModLoaded`（4 处）
- `module/impl/*`：`ServerTickEvents`、`ServerPlayConnectionEvents`、`ServerEntityWorldChangeEvents`、`ServerLivingEntityEvents`
- `config/ConfigManager`、`lang/Lang`：`FabricLoader.getGameDir`

**结论**：抽 6 个接口即可覆盖 —— `IPlatform`（isModLoaded/getGameDir）、`IEventBus`（tick/join/quit/entity-death/world-change）、`INetwork`（send/receive）、`IKeybind`、`IConfigDir`、`IBlockEntityFactory`。

## 2 1.20.1 → 1.21.1 代码级断点（按改动量排序）

| # | 断点 | 1.20.1 现状 | 1.21.1 写法 |
|---|---|---|---|
| 1 | **物品 NBT** | `stack.getOrCreateNbt()` / `stack.hasNbt()` | 组件化：`stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt))`；称号物品（R35 的 ItemCommander）要改 |
| 2 | **网络层** | `ServerPlayNetworking.send(player, Identifier, PacketByteBuf)` | 自定义 `CustomPayload` record + `PayloadTypeRegistry`；现有 5 个协议类（Shop/Task/Checkin/Config）全部重写 |
| 3 | **Identifier 构造** | `new Identifier(ns, path)` | `Identifier.of(ns, path)`（构造器已私有）；约 40 处 |
| 4 | **BlockEntity NBT** | `readNbt(NbtCompound)` / `writeNbt(NbtCompound)` | `readNbt(NbtCompound, RegistryWrapper.WrapperLookup)` |
| 5 | **附魔/组件相关** | `stack.getNbt()` 判空 | 同上，统一走组件 |
| 6 | **Java 版本** | 17 | **21**（`org.gradle.java.home` 要换 JDK21） |
| 7 | **Fabric API** | 0.92.6+1.20.1 | 0.102.x+1.21.1（`KeyBindingHelper`、`FabricBlockEntityTypeBuilder` 包路径有变动） |
| 8 | **NeoForge 侧** | 无 | 事件体系换成 `@Mod.EventBusSubscriber` + `NeoForge.EVENT_BUS`；方块注册换 `DeferredRegister` |

不受影响（可直接复用）：Brigadier 命令、Gson 存储层、`Ledger`/`SnapshotService`、`PathResolver`、全部配置 schema、全部 lang 资源、mixins（refmap 机制一致）。

## 3 迁移顺序（建议）

1. **期 0（1 周内）**：先把 `common` 包的 60 个纯逻辑文件抽出来，保证 `./gradlew test` 仍 100 绿（这步不碰 1.21.1）。
2. **期 1**：1.21.1 Fabric 单端跑通 —— 先 `Identifier.of`（机械替换）+ Java 21，再重写网络层，最后处理 NBT→组件。
3. **期 2**：NeoForge 端用模板 `redtale-fabric-neoforge-template`，把 6 个接口各写一份实现。
4. **期 3**：数据兼容 —— 1.20.1 的 JSON 数据（accounts/ledger/shop/warehouse/bounty/…）必须能直接读；**存储格式不要动**，只在 Java 侧做适配，这样回滚只需换 jar。

## 4 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 组件化改写称号物品 NBT | R35 已发出去的物品失效 | 读取时兼容 `getNbt()` 旧路径 → 组件新路径 |
| 网络协议重写 | 客户端/服务端必须同版本 | 协议里带 `_version`，不匹配直接拒绝并提示升级 |
| NeoForge 事件语义差异（如 `AfterPlayerChangeWorld`） | 维度/群系进度漏记 | 双端各写一次，单测用假事件驱动 |
| 第三方软依赖版本（FTBQ / Contact / SDM / StyledChat） | 编译期 jar 不通用 | `libs/` 里按 1.21.1 版本各放一份，`build.gradle` 按版本挑 |

## 5 待确认（需 Aki 拍板）

- 1.21.1 是否仍要 **Fabric 单端**先上线（NeoForge 后补），还是两端同时发？这直接决定期 1/期 2 是否串行。
- 称号方案在 1.21.1 是否继续用 StyledChat（需确认它 1.21.1 的 display_name 命令没变）。
