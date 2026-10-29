# Android 14 分支的 LSPosed 配置兼容改动

此分支以 `a14_eol` 的 `5f1f8ab4483278055718160efcdd209d326d03b8` 为基线，解决 New XSharedPreferences（NSP）弃用后的配置传递问题。Android 最低版本仍为 14，原有功能与推荐作用域保留。

## 支持边界

- 使用 libxposed API 101 入口，`minApiVersion=101`、`targetApiVersion=101`。需要支持该 API 和 Remote Preferences 的 LSPosed。
- Android 14 的既有 API82、HookTool、EzXHelper Hook 实现作为兼容后端保留。LSPosed 官方明确 [target 101 模块不受 target 102 的 legacy API 禁令影响](https://t.me/LSPosed/314)。因此不能直接把目标版本改成 102；本改动也不宣称已经实现 API102 原生 Hook 或热重载。
- 不再声明或读取 NSP，不再使用 WORLD_READABLE、全员可写权限辅助或文件监听器维持权限。不再靠 Hook 模块自身判断激活，也不再复制 LSPosed 私有数据库查询作用域。
- 旧 NSP 自动迁移覆盖此模块自己的主用户配置，包括 `/data/misc/<random>/prefs/[0/]com.sevtinge.hyperceiler/hyperceiler_prefs.xml` 及同名 `.bak`。未验证其他旧目录布局或跨用户迁移。

## 设置如何生效

例如，在界面中修改一个桌面设置：

1. 设置页仍写入应用私有的设备保护存储（DPS）；备份恢复和 AndroidX 设置页沿用同一配置。
2. 官方 Xposed service 连接后，把完整、保留类型的配置同步到框架 Remote Preferences。服务未连接或提交失败时，保留本地设置，在重连或下一次修改时重试。
3. 目标进程从框架读取启动配置，system_server 不需要等待 ContentProvider。CorePatch 读取同一份实时配置，不再单独打开 XSharedPreferences。

实时观察者也通过目标进程中的官方配置监听器触发：先替换完整快照，再沿原有 Handler 派发变化。这样桌面标题等功能不会因旧 ContentProvider 通知先到而读到上一次设置。

一次备份导入可能产生多个键变更通知，同一完整配置成功发布后会跳过重复快照；设置变化、提交失败后的重试或服务重新连接仍会完整发布。

改动保留 Boolean、Integer、Long、Float、String、StringSet 类型，处理删除键和清空设置。迁移完成前不会把可能为空的本地配置发布到框架覆盖旧配置。

## 旧配置迁移与安装

首次启动会在后台只读扫描本模块旧 NSP 文件；成功读取后先在应用私有目录备份，再回到主线程导入。最新源损坏、来源冲突、读取或持久化失败时，保留源与重试记录，不把迁移标记为完成。迁移期间用户新修改或删除的设置优先。

自动迁移需要授予 HyperCeiler Root 权限。如果收到迁移失败提示，授予权限后强行停止应用再重新打开。新版本配置文件位于私有 DPS，不再依靠改变文件权限分享。

如果决定放弃导入旧配置，可在设置中明确选择“重置全部设置”，再强行停止应用并重新打开。重置意图和重置后的配置单独持久化；只有私有配置与完成标记均写入成功，才恢复向框架同步。普通设置修改不会跳过迁移失败，旧 NSP 源文件也不会被删除。

**自行构建的 APK 与上游正式 APK 通常签名不同，不能直接覆盖安装。** 如果必须卸载旧版本，先使用旧版导出配置备份；卸载可能删除旧数据，自动迁移不能恢复已经被删除的文件。既有手动备份不包含总激活开关，导入后仍需重新同意并启用模块。

未设置项目签名配置时，debug/release 构建产物仍使用项目现有的调试签名回退。本 fork 的正式 Release 会在发布前使用固定 fork 密钥重新签名；密钥只保存在维护者本地，不上传仓库或 Release。正式发布包与上游正式版、先前临时 CI 包的签名不同，不能直接覆盖安装。

应用“关于 → 检查更新”打开 [Bigzhangbig fork 的最新 Release](https://github.com/Bigzhangbig/HyperCeiler/releases/latest)，源码入口也指向此 fork 的 `a14_eol` 分支。此入口手动查看发布页面，不宣称自动版本检测或自动安装。

## 构建与检查

沿用项目的 JDK21、Gradle9、Android SDK36、Build Tools36.0.0。

先设置 `JAVA_HOME` 与 `ANDROID_HOME`。源码契约和配置逻辑测试不需要上游私有包读取凭据：

```sh
python3 -m unittest discover -s tests -p 'test_*.py'
./gradlew -p tests/preferences test
```

独立测试工程直接编译实际生产配置类，使用 Android SDK 接口和 JVM 上的 XML 解析实现。它覆盖类型、删除/清空、断线重连、失败提交、并发、旧目录选择及迁移重试，不能替代 Android Binder 或系统启动验证。

完整应用仍依赖 GitHub Packages 上的 `fan.miuix:*:1.0.10.1` 和 `hyperceiler-expansion:1.0.0`。按项目现有方式提供 `GIT_ACTOR`、`GIT_TOKEN` 环境变量；凭据需要有读取对应包的权限。不要提交令牌或签名文件。

原分支同时声明了当前仓库未提供的 `fan.miuix:haptic:1.0.10.1`。已核对发布仓库：现存独立 haptic 版本从 1.0.10.5.2 开始，而当前固定的 core 1.0.10.1 已包含 HapticCompat 等相关类，因此移除重复的独立包声明，保留原有触感支持和其余 MIUIX 版本。CI 会检查 core 中这些类仍然存在。

Folme 1.0.10.1 的发布元数据也引用该不可用包，构建规则仅将这一固定版本的错误依赖改为同版本 core；CI 会核对 Folme 的 Haptic 类引用有实际提供者，不使用全局排除规则掩盖其他版本问题。

```sh
./gradlew :app:assembleDebug :app:assembleRelease
python3 tests/check_apk_contract.py
```

APK 检查同时验证 debug 和经过优化的 release 包中的现代入口描述、入口类实际保留在 DEX 中、API 目标、60 个作用域、官方服务 Provider，以及 legacy/NSP 注册未残留。完整构建或 APK 检查失败时，不能把源码契约测试的成功当成发布通过。

`.github/workflows/lsposed-compat.yml` 接入上述检查，并尝试使用 Actions 的包读取权限构建。该流程只保留构建产物，不向 Telegram 发布。

## 需要 Android 14 设备完成的验收

1. 从旧 NSP 版本保留配置升级，核对普通开关、数字、字符串、应用集合和总激活开关；确认迁移后再次启动不覆盖新设置。
2. 修改依赖实时更新的设置，例如桌面图标标题，确认当前目标进程得到新值并重绘；验证删除和重置不会残留旧配置。
3. 重启目标应用和设备，检查普通应用与 system_server 都使用框架配置；覆盖开机未首次解锁场景。
4. 在既有使用场景中检查 CorePatch 动态子开关、资源功能和故障日志；构建通过不代表这些系统功能已验证。
5. 临时停止框架服务或使同步失败，确认本地设置仍保留、提示未同步，恢复连接后重新同步。

可搜索日志 `HyperCeiler`、`Android 14 hooks initialized with framework remote preferences` 与 `HyperCeiler:Application` 定位入口、迁移和同步阶段。CI 的完整构建与 APK 检查只验证构建产物；上述 Android 14 设备行为仍需实际验收。
