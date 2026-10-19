# libs/

构建前把下面两个 jar 放到本目录。它们**不随仓库分发**（版权原因，已在 `.gitignore` 里排除）。

| 文件名 | 模组 | 版本 | 用途 |
|---|---|---|---|
| `ftb-quests.jar` | FTB Quests (`ftbquests`) | 2001.4.13 | 编译期引用 `Reward` / `RewardTypes`，注册 `redtale_economy:money` 奖励类型 |
| `ftb-library.jar` | FTB Library (`ftblibrary`) | 2001.2.9 | FTB Quests 的依赖，`Icon` 等类来自这里 |

获取方式：CurseForge / Modrinth 下载 **Fabric 1.20.1** 对应的上面版本，去掉文件名前缀直接放进来即可。

只参与编译（`modCompileOnly`），**不会**打进 `build/libs/redtale_economy-*.jar`。
运行时是否装上由服务器决定——`FtbqIntegration` 会先 `isModLoaded("ftbquests")` 再注册，没装时功能静默跳过。
