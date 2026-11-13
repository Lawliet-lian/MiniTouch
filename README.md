# MiniTouch

## 打包调试 APK

准备 JDK 17、Android SDK Platform 34 和 Gradle 8.4。首次构建还需要下载项目依赖。Android SDK 路径可通过项目根目录下的 `local.properties` 配置，例如：

```properties
sdk.dir=/Users/lawliet/Library/Android/sdk
```

在项目根目录执行：

```bash
gradle :app:assembleDebug
```

本机如果没有将 Gradle 加入 PATH，可以使用现有的 Gradle 8.4：

```bash
cd /Users/lawliet/Documents/WorkSpace/Lawliet-lian/MiniTouch
~/.gradle/wrapper/dists/gradle-8.4-bin/1w5dpkrfk8irigvoxmyhowfim/gradle-8.4/bin/gradle :app:assembleDebug
```

构建成功后，调试签名的安装包位于 `app/build/outputs/apk/debug/MiniTouch-debug.apk`，可复制到 Android 9 车机安装测试。项目目前未配置正式发布签名。

安装
```bash
adb install -r /Users/lawliet/Documents/WorkSpace/Lawliet-lian/MiniTouch/app/build/outputs/apk/debug/MiniTouch-debug.apk
```

## 无障碍自动恢复（免 root）

安装新版后，通过已授权的 ADB 连接执行一次：

```bash
adb shell pm grant com.lawliet.minitouch android.permission.WRITE_SECURE_SETTINGS
```

设置页的“自动恢复无障碍”默认开启，可随时关闭。功能随悬浮球服务运行，每 10 秒检查一次，仅向最新启用列表补入 MiniTouch，保留其他服务。每次开机最多尝试恢复 3 次，两次恢复至少间隔 30 秒；次数跨应用进程重启保留，达到上限后需下次开机才会再次恢复。未获得权限时不会写入设置。

手动关闭系统无障碍前，请先关闭自动恢复开关。关闭开关会停止后续恢复，不会关闭当前已连接的无障碍服务。需要撤销一次性授权时执行：

```bash
adb shell pm revoke com.lawliet.minitouch android.permission.WRITE_SECURE_SETTINGS
```

该权限可写入多种系统安全设置，应仅授予可信版本。车机是否跨重启保留此授权、是否继续覆盖启用列表，需要实机重启验证。系统设置列表的读取和写入不是原子操作，其他进程恰好同时修改列表时仍有竞争可能。
