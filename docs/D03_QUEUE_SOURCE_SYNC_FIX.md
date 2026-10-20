# D03 CI 队列工程源码同步修复候选

已获产品限定ACK后，最终交付 `8d4827c41d4d1f77bdc3020e48fec77d100086f2` 本机必要全量合同实际1370项、受控原生41项通过，
方法清单/源码输入/Room v15严格核验通过，随后正常推既定开发分支。main仍7383e502418f5f9fc0c540ef2072742359343d40。

push自动触发唯一 [CI39477552413](https://github.com/T1doo/AIyou/actions/runs/39477552413)，精确8d4827c，attempt1，终态 **failure**。
实际Room v15与完整wire合同先通过；日志中的AI_PROTOCOL_XML_SNAPSHOT_JSON明确178/71/1121=1370项通过，
源码输入1bbc1e215799a9c8a308105da1a445fbad259711c7e5acff22ddbfae89791d7e。
第7步record-queue编译失败：syncProduction漏了实际ControlledCaptureAudio.kt，MixedCaptureController及包控制器因此无法解析原音类型。
后续原生/Android APK/Compose/lint/Preview门均skipped。不能把always证据保全成功称为这些门通过，也没有本次APK。

修复 `c84b339dcae9a0f254e1aec617a4ca61657c616f` 只在checks/record-queue/build.gradle.kts增加一行from，
编译实际ControlledCaptureAudio源文件。生产app/core、Manifest、依赖、schema、测试断言/门/超时/工作流均不改。
同类源码清单已对照：ai-protocol之前已经包含此文件，当前独立queue是唯一遗漏。

实际执行 `python3 scripts/jvm-gradle.py -p checks/record-queue test`，随后 `python3 scripts/verify-test-results.py --queue`。
queue实际 **188项**，0失败/错误/跳过，既有必需方法与子进程证据核验通过。
该次测试执行于8d4827c工作树加这一行同步；随后逐项证明harness及60个实际编译同步文件的字节与c84b339提交一致，
不虚称测试开始时HEAD已为c84b339，也不为更改提交标签重跑相同套件。
harness SHA256 `3f098cfd38bf1dbba3db6c93014f9d466af726522d19faa779b07dbc2a16263f`。

普通CI原始job日志759114字节，SHA256 `e8a06f333f985fc3a0498adf4f8f73565af7c5605a7a7e7f925f92c8734101ff`。
仅有d01-reports产物元数据：12290306302，769032字节，报告digest sha256:dd23b41ff4fc313dcf7a1f9426289a3fcad558ef0e5709fa57593887fe5f754f。
未下载artifact或APK；原始普通日志不含D02_CI_FILE文本帧，因此不声称获得其中未公开的文件。

[失败与修复验证证据](evidence/D03_QUEUE_SYNC_CI_FAILURE_EVIDENCE.zip)：329469字节，203项文件清单，
SHA256 `fe625eb509facfd1f9307fc9629e32b3a66b9afd053b6546e51bddf67774b4da`。包含终态/steps/原日志、产物元数据、
实际本机1370/41 XML/输入，以及188队列XML/源码字节映射；CRC与逐项大小/hash已核。
修复尚未推送；依父侧“独审影响变化再推”，影响复审后才正常推并执行一次新SHA的因果CI，不重跑失败的8d版本。
真实权限、录音、播放、通知和设备验收均未执行，D03/D04/D07不称整体验收完成。
