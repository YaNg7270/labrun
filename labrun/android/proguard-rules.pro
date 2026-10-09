# 事件与导出包的序列化类：kotlinx.serialization 自带 R8 规则；此处额外保留类名，
# 保证混淆后 JSON 的 "type" 区分字段与旧版本数据一致（类名不参与序列化，仅为排错可读）。
-keep class labrun.core.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
