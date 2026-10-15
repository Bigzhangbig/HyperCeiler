# Android 14 / libxposed API102 原生兼容

从已通过构建的 API101 修复提交 `e866c3dfc996b1bb094706e9bef641b207bd4bac` 继续迁移。旧配置修复分支和原产物保留；当前分支为 `fix/lsposed-api102-native`，应用 versionCode 为4259。

## 支持范围

模块最低和目标 Xposed API 均为102。目标是让原 Android14 功能实际通过现代 Hook API 执行，模块和依赖的 DEX 不再引用框架旧 API。API102明确禁止目标102模块调用旧API，依据为[官方102源码包](https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0-sources.jar)中的 `XposedInterface.API_102`。

原生 API102 与热重载是不同的能力。本分支关闭 `autoHotReload`，入口也拒绝热重载，因为功能静态状态、资源替换及观察者还没有完整的重载交接实现。修改设置仍沿用既有实时监听或重启目标进程的生效方式。

## Hook 迁移

- 使用官方 libxposed102.0.0、HookTool3.2.2及EzXHelper的原生 `xposed-api-101:3.2.0-preview1`。后者的构件名称含101，但实际调用现代 `XposedInterface.hook`，可在102接口上编译并执行，不再依赖 `xposed-api-82`。SuperLyric/HyperFocus现有构件未发现旧框架引用，继续保留。
- 入口继承原生ModuleEntrance，接收实际框架生命周期对象。12个原HC模块接到原生AbsModule/AbsHook，保留原功能代码。三个依赖Application.attach完成的模块仍使用AFTER，避免被新入口的BEFORE通知提前触发。
- 其余Java功能使用模块自有 `hook.api` 辅助层。回调语法便于保留原业务，但注册、优先级、参数传递和原方法调用都直接走API102；不会反射或调用框架旧API。非静态原方法用显式 `ORIGIN + invokeSpecial`，避免虚分派误入子类覆盖方法。
- 自有桥隔离普通回调错误，保留主动返回值/异常替换，并显式使用PASSTHROUGH。文件阻止功能的原生Ez调用也显式选择该模式，防止默认保护模式吞掉主动异常后继续文件操作。
- 反射查找代码依据Apache2许可移入自有ReflectionHelper，MemberUtils对应比较逻辑为自有ParameterMatching，ClassUtils使用Commons Lang。类和参数缓存按真实Class对象区分，避免插件ClassLoader中的同名类发生缓存串用。来源声明及Apache2许可随APK assets一起打包。

每个自有回调注册独立原生Hook以保留跨模块优先级。API102 Chain暴露当前拦截层的输入参数；不保证某个回调的after能读到下游另一个回调替换后的参数数组/receiver。当前业务的定向审查未找到依赖该细节的实际组合；不能把此辅助层宣称为任意旧模块的完整兼容库。

资源功能继续使用已有ResourcesLoader/ResourcesProvider、Resources/TypedArray方法拦截及原生ResInjectTool。删除的旧XResources布局辅助函数没有实际调用点；保留原60个作用域及25个文件中的既有资源替换功能。构建和静态检查不能证明每个ROM上的资源效果。

## 配置迁移

继续使用私有DPS配置、官方Remote Preferences、旧NSP只读迁移及失败重试。首次自动迁移需要Root权限；发生错误时保留旧源，避免空配置覆盖。若明确放弃旧设置，可在设置中选择“重置全部设置”，然后强停重开；重置意图与预期快照会单独持久化。

API101分支的[迁移与安装说明](https://github.com/Bigzhangbig/HyperCeiler/blob/fix/lsposed-preferences-compat/docs/lsposed-api101-compatibility.md)仍适用于配置备份。自动迁移只覆盖主用户的已知旧NSP目录布局，不能恢复已经随卸载删除的数据。

## 验证与复现

环境：JDK21、Gradle9、Android SDK36、Build Tools36.0.0、Kotlin2.3.0、AGP8.13.2。原生helper需要Kotlin2.3，相应升级AGP以使用支持该元数据的R8。

```sh
python3 -m unittest discover -s tests -p 'test_*.py'
./gradlew -p tests/native-hooks test --console=plain
./gradlew -p tests/preferences test --console=plain
./gradlew :app:assembleDebug :app:assembleRelease --console=plain --max-workers=2
python3 tests/check_apk_contract.py
```

独立测试直接编译生产桥/反射/配置类。原生helper测试使用真实发布AAR和官方102接口，框架链与异常模式是可控测试替身，用来检查交互契约，不是Android实际方法Hook。自有桥绕过回调的隔离变异实验使16项中的10项行为失败；插件缓存旧实现使6项中的4项失败；原方法虚分派反例先失败后修复。

APK门禁同时检查debug和优化后的release：API102描述、实际保留的入口类、60作用域、服务Provider，以及所有DEX中没有旧Xposed或AndroidAppHelper/XResources/XModuleResources/XResForwarder类型和反射类名。该门禁已确认会拒绝上一轮API101实际APK。

完整构建仍使用项目现有GitHub Packages依赖与认证方式；CI可使用自己的仓库令牌。本地没有读取凭据时不要提交令牌或更改全局认证。

## 尚需设备验证

当前没有连接的Android设备，也未安装到手机。仍需Android14与支持API102的LSPosed实际验证：旧配置迁移、普通应用/system_server注入、开机未解锁配置、桌面/系统界面实时功能、资源替换和CorePatch。通过测试或生成APK不等于这些功能已经实机通过。

未配置发行签名时，CI仍使用临时测试签名，与上游正式签名不同，无法直接覆盖官方版；不同CI运行的测试证书也可能不同。安装前先导出旧配置，谨慎处理需要卸载的情况。
