# legacy/devsrc —— 开发期夹具（**不参与构建**）

这 4 个类是 B 队列验收 T38 / T39 时写的一次性截图/探针夹具，**不在 `main` 源集内**，
所以不会进发行 jar。`LegacyInit` 用 `Class.forName` 按类名可选调用（找不到就静默跳过）。

## 为什么移出来

原先放在 `legacy/java/.../legacy/dev/`，而 `build.gradle` 的 `main { java { srcDir 'legacy/java' } }`
会把它们编进 `redtale_economy-*.jar`（实证：jar 里有 4 个 `legacy/dev/*.class`）。
夹具里含客户端类（`MinecraftClient` / `Screen`），随发行版下发属于污染。

## 如何在需要时启用

临时把源集加回去（**别提交这行**）：

```gradle
sourceSets {
	main {
		java {
			srcDir 'legacy/java'
			srcDir 'legacy/devsrc'   // ← 加这行
		}
	}
}
```

然后放 flag 到 `run/`（即 gameDir）：

| 夹具 | flag | 产出 |
|---|---|---|
| `PackageShotHarness` / `PackageShotClient` | `run/b14-pkg-shots.flag` | `run/screenshots/b14-pkg-<tier>-f<N>.png`，玩家钉在固定锚点，3 档包裹各连拍 14 帧 / 5 tick |
| `TooltipShotHarness` / `TooltipShotClient` | `run/b16-ttip.flag` | 逐行 `getTooltip` 落盘 + HUD 截图 3 帧，跑完自动退出 |
| `ItemShotHarness` / `ItemShotClient` | `run/b36-item-shots.flag` | `run/screenshots/b36-item-<item>-f<N>.png`：自绘纯黑屏 + 物品 6× 居中，隔 10 tick 连拍 8 帧（`surprise` 正样本 / `package_normal` 空动画负对照），判据 `_b38_item_anim.py` |

⚠ `legacy/devsrc` 里有几个旧夹具引用了已删除的常量（`TooltipShot*`），临时启用源集时要
`exclude 'com/akira/redtale/economy/legacy/dev/TooltipShot*'`，否则 `compileJava` 必红。

两个 flag 都会让客户端**提前自行退出**，且 `run/` 是 A/B 共用的 gameDir
⇒ 跑之前确认没有别人正在 `runClient`，跑完把 flag 删掉（见 `_LESSONS.md` 第 14 轮 / 第 10 轮）。

## 验收判据

`C:\Users\Administrator\WorkBuddy\2026-09-14-20-38-30\_LEGACY_VERIFY.md` §2.1~§2.3、§3.7。
