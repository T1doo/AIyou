# R2b 首次精确提交 CI 与四项 lint 修正候选

本文保留失败与候选时点事实。后续限定 ACK、正常推送及新精确 SHA 的实际成功结果见
[R2b CI 封存](D07_R2B_CI_CLOSURE.md)。

父侧已对产品 `3e8727bffb02aa141e45f792661573bc9320ffc6`、交付
`a02af5b722907e520a9010db3eb7e0e1af546c70` 给出限定实现 ACK，不是测试/设备验收。
核对已审包、14 个提交与完整差异一致后，已正常推送开发分支；main 未改，没有强推。

[CI 39440316782](https://github.com/T1doo/AIyou/actions/runs/39440316782)，push 自动触发、attempt 1，
精确 SHA `a02af5b722907e520a9010db3eb7e0e1af546c70`，终态 **completed / failure**。
失败项只有 Android lint；普通 job 日志接口成功，原始解码文本已按 UTF-8 精确保全。
没有重跑失败原版本、重复派发 CI 或下载 APK/artifact；旧 Azure 拒绝资源没有重试。

| 本次 CI 的实际范围 | 结果 |
| --- | --- |
| 实际 Android Room v15 KSP / 前代导出与打包合同 | PASS |
| AI/领域/远端协议 JVM、方法清单/XML/输入快照 | PASS：178 + 71 + 1082 = 1331 |
| 队列 / Python / tokens / 受控原生链 | PASS：188 / 168 / 一致 / 41；全部非空结果门通过 |
| native observer / 组件 / pre-open 密文证据 | PASS：8 / 7 / 21 |
| `:app:assembleDebug` / 合并 APK 权限检查 | PASS；无 INTERNET |
| Compose JVM interaction 全量 / 同轮子集证据 | PASS：304；子集不另加总或重复计为新测试 |
| instrumentation APK / DEX 方法清单 / 许可与 ZIP 对齐静态检查 | PASS；没有设备执行 |
| Android lint | FAIL：4 errors、0 warnings、1 hint；严格规则未降低 |
| Preview 生成、验证、完整性、PNG 打包及保存 | PASS |
| 旧 lazy-node 专用独立复现 | SKIP：本提交没有 `[ui-lazy-repro]` 触发标记；不计作 PASS |
| 实际权限/闹钟/通知/重启/OEM/SQLCipher/16 KB/性能、手动视觉验收 | NOT RUN |

本次 debug APK 的 CI 路径为 `app/build/outputs/apk/debug/app-debug.apk`，日志报告 SHA256
`f93b198b9e36d663409b59e46c33830887776480cdfba5c1b334d7e4948580b5`。
`aiyou-d01-debug` artifact ID `12275930600`，归档 19444937 字节，服务端报告 digest
`sha256:9af76dc64c5232a94e6b1ea84e2ffae40ea13be93c9c0cb535bbe8f3bcf1057d`。
完整 16 项 artifact 元数据均已核对同一 head SHA、非空、未过期与 digest 格式；未下载/重算归档字节，归档 digest 不当作 APK digest。

## 四项确定诊断与最小修正

修正产品提交：`52f36c0489f9ca3d767b3c51d3e42cf21b174f96`。该提交尚未推送，也没有第二次 CI。

| a02af5b 实际诊断 | 修正候选 |
| --- | --- |
| Driver:30 `InlinedApi`，仅模拟 platformApi guard 不能证明 POST_NOTIFICATIONS 的真实系统版本 | 增加直接 `Build.VERSION.SDK_INT < 33` 短路；默认实际系统语义保持一致 |
| Runtime:98 `StaticFieldLeak`，单例字段 app 的静态类型为 Context | 仍从 `context.applicationContext` 取得对象，并明确为 Application 类型，约束不保留传入组件 Context |
| Driver:78 `UseKtx` | 同一不变 URI 字符串改用现有 `String.toUri()` |
| Dialog:96 `UseKtx` | 同一 package URI 改用 `String.toUri()`，授权与 route/editor 核对不变 |

应用已通过现有依赖锁使用 core-ktx 1.18.0；受控 native harness 只补上同版本真实 AAR 来编译该 inline API。
通过定向 `--update-locks androidx.core:core-ktx` 生成锁，唯一新增条目为 `androidAars`；生产依赖、Manifest、全局版本及其他锁未改。
该 AAR 本地 SHA256 `c7c511e3df838fcbcb61ace36944de6bb7c10eb597be546974f001119939c85d`。
没有 lint suppress/ignore/baseline、降低 warningsAsErrors、增加权限、接受新 SDK 条款或改变提醒业务门。
非失败项的 MainActivity `AutoboxingStateCreation` hint 保持原样，不改称 0 hint。

## 候选实际回归与未验范围

`python3 scripts/jvm-gradle.py -p checks/reminder-native :test`，过滤
`AndroidReminderDriverTest`、`ReminderJobChainTest`、`ReminderJobFailureLeaseTest`：
**PASS 13 = 6 + 4 + 3**，35s，失败/错误/跳过均 0；执行 HEAD 为上述修正产品 SHA。
生产副本、完整输入、方法/XML 清单逐项核对，指纹
`09aae81dab1565466f5dba8d0172b8463617c2e0e83fd595bc360b7749f771b3`。
未重跑无变化全量套件；a02af5b 的旧通过结果不改称修正提交全量通过。

本地 `check-sdk.py` 的只读 gate 未满足（ANDROID_HOME 未指向已确认许可 SDK），没有安装/接受新 SDK 条款。
修正提交的 Android lint/APK/Compose **NOT RUN**；Dialog 不在此受控 harness 编译范围。
必须在独审后正常推送并跑新精确 SHA 的必要 CI，不能将本地 13 项通过当作 lint 已关闭。

[失败与候选证据 ZIP](evidence/d07-r2b-ci-a02af5b-lint-fix-20261020.zip)：386253 字节，36 项，
SHA256 `2e4296a9a4ad49290891930598b1609fb122999cb38aeae58a33372ff9b3ffcc`；逐项哈希/CRC 已核。
原始 job 日志 1353743 字节，SHA256 `71dc5346b6a86a6da7a667dd4ae3a1901f7e859b15bb78952b9125423c5cc00c`；
JSON 原文与解码 UTF-8 文件逐字节复核。证据含真实终态、SKIP、全部 artifact 元数据、四项原始诊断及候选 XML。

远端开发分支仍 `a02af5b722907e520a9010db3eb7e0e1af546c70`，main 仍
`7383e502418f5f9fc0c540ef2072742359343d40`。修正候选等待独审，不推送、不启动重试。
保持 LOCAL_ONLY、无后台/真实模型调用、无新费用/凭据/安全网络权限；D03 ASR/OCR、D04 绑定、D07 整体验收仍 OPEN。
