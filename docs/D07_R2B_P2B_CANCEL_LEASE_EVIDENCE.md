# R2b 同轮残留 P2：取消收尾、OPENING 释放与异常 Job 租约

产品提交 `62bb70feceff13f8c74ad2053e9c09f70bd63718`，基于上一审查包 HEAD
`963157b4fdfc2f40f33089008de344d6964d6a04`。本轮只处理父侧已确认的三项残留；
其余复审仍在进行，没有实现 ACK，不推送、不启动 CI、不扩展功能。

1. `prepareFront` 的失败清理把实际 `owner.closeAndAwait` 与 holder 清除放在同一 `bridge.runtime` worker 闭包。
   该闭包不可取消；完成返回调用方时的取消不再被误报成关闭失败。调用方仍收到取消，不返回成功 Pair。
   实际 close/drain 失败仍保留 sealed owner、repository/native handles 与主 key，不准第二次 factory。
2. 普通释放接受已准备资料但尚未完成初始读取的 OPENING，进入 RELEASING。
   holder 只有在实际关闭确认后才清除并允许 NO_FRONT；显式锁、未知访问或关闭失败不转为冷准入。
   OPENING 尚无 holder 时不猜测先前 owner 已关闭；旧票据仍退休，不迁入新冷候选。
3. Job 正常耗尽仍用最终空 dequeue 自动结束。异常无法走到空 dequeue 时，主线程核对当前 Run、active 和 submission 代次，
   一次认领终结并调用 `jobFinished`，不请求重试；不调用 `completeWork`，不补写业务事件、claim 或成功回执。
   stop/替代运行先赢则不调用旧终结回调。快速 guard 内仅排入 framework 本地消息，没有数据库 IO 或 Binder 等待。
   framework finish 自身结果未知时不推导成功、不重复发送；真实平台回调、唤醒锁与配额行为尚未设备验证。

平台依据：[AOSP Android 13 JobService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/apex/jobscheduler/framework/java/android/app/job/JobService.java)
规定异步执行的终结/停止与系统唤醒锁关系；
[JobServiceEngine](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/apex/jobscheduler/framework/java/android/app/job/JobServiceEngine.java)
将 finish 排入本地主线程消息。此合同依据不等于设备 Binder 已验收。

| 本轮实际执行 | 结果 |
| --- | --- |
| 旧代码 factory 定向回归 | RED：4 项中 1 失败，已完成关闭被错误归类为关闭未确认 |
| 旧代码三类统一定向回归 | RED：10 项中 3 失败：取消后同进程不能重开、OPENING 确认关闭后没有冷候选、coldOpen 异常没有平台 finish |
| 修正后五类受控原生回归 | PASS：17 = 工厂 4 + 生命周期 3 + Job 异常 3 + 停止代次 3 + Job 链 4；失败/错误/跳过均 0，33s |
| 生产源码副本、方法/XML 清单、执行输入与提交 blob 比较 | PASS；所有捕获输入逐项等于产品提交字节；实际主机 KSP v15 与提交字节相同 |
| 其余 15 项受控原生、主机全量、队列、Python、tokens | 本轮 NOT RERUN；上一轮 9b49bf8 的结果保持历史证据，不改称本提交全量通过 |
| Android APK/AAR metadata/lint/Compose/Preview、CI、真实权限/通知/闹钟/重启/OEM/设备 SQLCipher/16 KB/性能 | NOT RUN |

执行命令：`python3 scripts/jvm-gradle.py -p checks/reminder-native :test`，分别过滤
`ReminderFrontFactoryFailureTest`、`ReminderFrontLifecycleTest`、`ReminderJobFailureLeaseTest`、
`ReminderJobStopScopeTest`、`ReminderJobChainTest` 五类。没有并行 Gradle、重复 CI 或第二写入者。
新增回归使用生产 bridge/gate、实际受控 SQLite 和实际组件；队列、finish 回调和效果端口受控，不能称真实 OS 租约已释放。
测试中未执行到的快照比较已使用任务内容与空回执断言，避免错误比较 `ReminderRuntimeView` 对象引用。

测试执行时 HEAD 是 `963157b4fdfc2f40f33089008de344d6964d6a04`，修正在工作树中；
随后只提交已测试的相同字节。`verified-targeted-results.json` 逐项证明捕获输入与新产品提交 blob 相同；
没有仅为更改 HEAD 元数据而重复无变化套件。输入指纹
`3180794ee708f10081fd0d1a63acbf77fcafc9444f93f014df6fcc3570cf8096`。

[本轮证据 ZIP](evidence/d07-r2b-p2b-cancel-lease-20261020.zip)：46094 字节，18 个条目；
SHA256 `9c7977d7b12a77d66268b81c92578407929ad46be14b5f688a533b6bc2b4861f`。
包含原始 red/green 日志、XML、执行输入与提交绑定收据；逐项哈希和 ZIP CRC 已校验，旧证据和 Library 包保留。

远端开发分支仍 `9072bb941000a5956c9229bb29b9372657aa8c70`，
远端 main 仍 `7383e502418f5f9fc0c540ef2072742359343d40`。
产品提交领先远端 7、落后 0；后续证据文档提交不改产品代码。保持 LOCAL_ONLY、无后台、无真实模型调用；
无新权限/费用/凭据/SDK 条款接受，无被拒 Azure 下载重试。原方案文件未改。D03 ASR/OCR、D04 绑定及 D07 整体验收仍 OPEN。
