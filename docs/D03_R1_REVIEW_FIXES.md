# D03 R1：两项独审 P2 修订

本次只修复 R1 实现独审提出的自动降音与文件选择暂停竞态。增量基线为
`d149099f5972d4d545918cb5c953b8c3eaf1c2b7`；最终受测产品为
`83010e1fa1ffeea9b87e45c222124e63be6d839e`。本文件之后的封存提交仅增加本文，
产品源码与该受测 SHA 一致。原方案、Manifest、生产依赖、数据格式与权限未改。

## 自动降音后停止并释放

`PlatformOriginalAudioOutput` 的真实 `AudioFocusRequest` 现在显式设置
`setWillPauseWhenDucked(true)`，仍使用未知内容类型，不把所有导入 WAV 都标成语音。
Android 26 起自动降音可能不回调原应用，故必须声明暂停意图才能依靠现有监听器停止播放。
依据：[Android 官方音频焦点与自动降音说明](https://developer.android.com/media/optimize/audio-focus#automatic-ducking)。

实际适配器回归核对请求属性，并打开、播放 JVM 模拟 AudioTrack 后触发真实监听器的
`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`：停止回调关闭适配器，释放轨道、放弃焦点、注销
noisy receiver。后续 GAIN 不创建轨道或恢复播放。控制器回归另核实队列中存在 PCM 时，
该停止回调结束输出并释放 PlaybackPin；前后台变化和迟到回调不会自动重播。

## 已唤醒但尚未准入 open 的选择保留

`Request.fileReady` 改为合流唤醒通道；选中的单一源保留在同一请求内。IO 被唤醒后，
在同一仲裁锁内检查退休、当前请求、取消、路由与前台，再清除选择并准入 open。
普通 pause 只等待下次同 owner、同 route 的 RESUMED，不结算为空闲、不丢选择。
取消、真实路由变化与退休仍终止请求，旧请求不得打开 URI。

确定屏障位于唤醒后、open 准入前；测试在此暂停，确认仍 PICKING、busy、草稿冻结、
零次 open；恢复两次也只 open/close 一次，并以真实 SQL/JCA 草稿回读核对原始字节。
同一屏障分别覆盖取消、路由变化和退休，均零次 open。生产屏障默认空操作。
已准入的系统 open 仍须等待真实返回并关闭，不把取消协程视为资源确认。

## 本次实际验证

以下均在受测 SHA 执行，XML 核对无失败、错误或跳过；不同门存在重叠，不合并宣称独立总数。

- PASS：`python3 scripts/jvm-gradle.py -p checks/ai-protocol :remote-text:test --tests 'com.t1doo.aiyou.records.ControlledCaptureAudioTest' --tests 'com.t1doo.aiyou.records.OriginalAudioControllerTest'`。
  两个完整类各 26 / 11 项，共 37 项；已核源码方法清单与 XML 一致。这是定向执行，未重跑完整 wire 合同。
- PASS：`python3 scripts/jvm-gradle.py -p checks/reminder-native :test`，全套 46 项；
  `python3 scripts/verify-reminder-native-results.py` 严格验证通过，输入指纹
  `770d1e9756cc173efe7fc2380b25b29a95f9456d26082fd51ca4902e201deea5`。
  199 个原生输入与实际 Room v15 导出均核验。该门是 API 35 JVM 模拟。
- PASS：`python3 scripts/jvm-gradle.py -p checks/record-queue compileKotlin`，生产 Kotlin 实际编译成功；未重跑 queue 业务套件。
- PASS：两工程 Sync 产物逐文件与生产源码匹配：wire data 73、wire VM 46、queue 63 项。

首次原生编译因测试读取可空监听器失败，改为 `checkNotNull`。其后新 AudioTrack 回归遇到
Robolectric 默认 Bluetooth shadow 对 API 35 不存在的 `BluetoothAddress` 类型进行签名反射。
仅在该测试方法配置空 BluetoothDevice shadow，隔离无关元数据反射；AudioTrack、焦点与
receiver 继续使用原有模拟机制并保留所有释放断言。没有验证 Bluetooth 路由或硬件。
最后调整注解顺序，让现有严格方法清单识别新测试；没有降低测试门。
各轮原始日志、失败 XML、最终 XML、输入清单与同步回执随本次独审包保全。

## 尚未验收

当前未推送、未启动 Android CI，等待独立复审。旧完整合同结果只作为原 R1 基线记录，
不能替代本次精确 SHA 的后续 Android 验证。APK、metadata、lint、Compose、Preview 均未执行；
本机没有配置已接受许可的 Android SDK，也未安装 SDK 或接受新协议。
真实设备、系统音频焦点/路由、真实 SAF provider、麦克风、权限、进程死亡、SQLCipher 原生、
16 KB、可访问性与性能均未验收。没有实录、真实模型调用或新增权限；D03 ASR/OCR、D04、
D07 的未验边界继续保留。
