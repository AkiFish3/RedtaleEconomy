# libs/dev/_disabled

这里的 jar **不会**被 `build.gradle` 加载（dev 循环只扫 `libs/dev/*.jar`，不递归）。

原因：sodium / iris / sodium-extra 把 `method_XXXX` / `class_XXXX` 这类 intermediary 名
**硬编码在 `@Inject(method=...)` 里，且不带 refmap**。dev 环境是 yarn 命名的，loom 的
remap 只重写 refmap，改不动注解字符串 ⇒ 启动时 `MixinApplyError`，客户端直接崩：

```
Mixin apply for mod sodium failed sodium.mixins.json:core.render.immediate.consumer.BufferBuilderMixin
  could not find any targets matching 'method_23918(Lnet/minecraft/class_293;)V'
Mixin apply for mod iris  failed mixins.iris.json:texture.MixinResourceLocation
  could not find any targets matching 'method_20208(Ljava/lang/String;)Z' in Identifier
```

已实测 `modRuntimeOnly` / `modLocalRuntime` / `modImplementation files()` 三种写法，全部崩
（2026-10-21 T41）。它们是**正式客户端**的性能/光影模组，dev 截图不需要，留在这里备查。

要临时启用：`mv libs/dev/_disabled/*.jar libs/dev/`，但 dev 客户端会崩。

## T39 三 tooltip 模组同场测试集（2026-10-22 第 16 轮，**移出 glob，不参与 runClient**）

复原方法（★不能直接 cp 进 libs/dev★，见 _LESSONS「dev 的 files() 不展开 jar-in-jar」）：

1. 以下 4 个可直接拷进 `libs/dev/`：`obscure_api-16.jar` / `trinkets-3.7.2.jar` /
   `spell_power-0.12.0.jar` / `extraspellattributes-1.3.2.jar`
2. 还必须抽两个 jar-in-jar（不然 loader 报缺依赖 / NoClassDefFoundError）：
   - 服务端 `[前置]cardinal-components-api-5.2.3.jar` → 解 `META-INF/jars/*.jar`（8 个模块）放进 `libs/dev/`
   - 上面 `spell_power-0.12.0.jar` → 解 `META-INF/jars/TinyConfig-2.3.2.jar` 放进 `libs/dev/`
3. `touch run/b16-ttip.flag` → `./gradlew runClient --offline --args="--quickPlaySingleplayer u6world"`

跑完必清：`libs/dev/*.jar` 全删（A/C 的 runClient 会一起吃到）+ 删 flag。
