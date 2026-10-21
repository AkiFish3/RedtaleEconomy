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
