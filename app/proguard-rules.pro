# 这些名字是跨进程 AIDL 契约与百度 CarLife 数据类，混淆后会破坏兼容，必须保留原名。
-keep class com.baidu.carlife.mixing.CastConfig { *; }
-keep class com.baidu.carlife.mixing.MusicInfo { *; }
-keep class com.baidu.carlife.mixing.aidl.** { *; }

# Keep the runtime decoder as a call boundary.  If R8 folds Sealed.open into
# callers, the protected protocol strings can reappear as plain dex constants.
-keep,allowobfuscation class io.github.iamr0s.carvex.protocol.Sealed { *; }

# --- 混淆强化 ---
# 允许 R8 放宽访问修饰符：内联与去虚拟化能走得更远，混淆后的调用图更难还原。
# （AGP 9 的 R8 full mode 已默认 -repackageclasses 到默认包，类名已无包结构信息，无需重复设置。）
-allowaccessmodification
