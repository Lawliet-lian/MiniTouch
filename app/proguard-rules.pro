# 混淆规则（骨架阶段占位）
# 无障碍服务由系统按 Manifest 名称实例化，需保留
-keep class com.lawliet.minitouch.accessibility.NavAccessibilityService { *; }
# 广播接收器由系统实例化
-keep class com.lawliet.minitouch.receiver.BootReceiver { *; }
