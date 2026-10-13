# D04 正式 Room/SQLCipher 验证边界

本切片把 source-bound 事务边界接入正式 Android instrumentation。设备执行仍为 **NOT RUN**；D02 设备验收、完整 D04 与 App 端到端均 **OPEN**。历史 host SQLite/HTTP 的233方法通过记录见 [D04_SOURCE_BOUND_STORAGE.md](D04_SOURCE_BOUND_STORAGE.md)，本切片不新增 host SQLite 业务覆盖来替代原生。

## 真实生成 schema 与已有 CI

来源是源码 `a8c2f5d6b4d52d90d54f7ea79095147fa1cde191` 的正常 push CI [run 38412041918](https://github.com/T1doo/AIyou/actions/runs/38412041918)、job `115346586017`。已授权 GitHub 只读工具确认 job 最终 `completed/success`；日志中 checkout 完整 SHA 和 `D02_GENERATED_STATE` 原始载荷一致。没有重跑该 CI。

`core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/4.json` 从 step35 的 Base64 原始字节取回，经大小及 SHA-256 校验后保存；未手写或推导 Room identityHash。

| 属性 | 真实值 |
|---|---|
| database.version | 4 |
| 原始字节数 | 18672 |
| SHA-256 | `50a1c0f8a66a9ee581fbab1fe68768bb024ee118157d5024d720e8287eee3f6f` |
| Room identityHash | `8795c16d3625d5d3af3322ed9fa003c8` |
| 业务表数量 | 8，旧5与新增3 |

独立只读核对：旧5个 entity 完整 JSON 对象（createSql、fields、PK、FK、indices）与真实 v3 一致。共享迁移的6条表/索引 DDL 与实际生成 v4 的8表结构一致；新增字段的 affinity、nullability、SQL defaults、主键顺序、外键列顺序/动作和索引唯一性/顺序均匹配声明。新增外键均 deferred；JSON 本身不能证明运行时触发器已安装，正式原生测试另查 `sqlite_master`。

原始日志、生成载荷、step/job状态及当时 APK 清单保存在被忽略的 `.local-evidence/d04/room-native-baseline/`。该旧 CI 已实际编译的清单是**旧19项加独立探针**，不能外推为新增8项的编译或运行证据。debug APK 当时为20,310,002字节，SHA-256 `0a20c82f4619ae4196afd73691ab012fec2fbfe33e6bea667b090c751a31b130`。

## 正式原生测试与严格清单

`SourceBoundTextRecordsIntegrationTest` 通过生产 `EncryptedEntryRepository.openWithKey`、其实际 `sourceBoundText` Room 适配及 SQLCipher 执行8个具名方法。测试使用独立合成目录和固定合成数据库口令，不设置提供商凭据或新 Keystore 身份。

1. 新建 v4：真实 Room 生成库、加密头、Room identity 与表/外键/索引/触发器。
2. 原生加密 v3→v4：用逐字复制的真实 v3 导出建库，保留原文版本、独立草稿、local job 和合法 WRITING 附件行，迁移后关闭/重开再次核对。
3. 撤销先提交：同一文件的两个独立 Room 实例，真实 writer 顺序令迟到提交被拒绝。
4. 结果先提交：提交候选后另一个 Room 撤销，候选被删除，重开后仍无候选。
5. 部分写入故障：真实 trigger 在 annotation INSERT 后的 job UPDATE 上 ABORT，整事务回滚；关闭/重开核无候选且 job 未完成。
6. 原文变更和墓碑：旧版本或已删除源的迟到结果均被拒绝。
7. 幂等、冲突与来源绑定：相同候选恢复原记录，不同候选及异源/不一致 job 元数据不能覆盖原文。
8. Unicode：半开 code-point 范围与引用文本必须精确匹配。

共享 `NativeRecordsSchemaFixture` 只用真实导出的 v2/v3 test assets 建夹具并执行其 DDL/索引/setupQueries；v4 asset仅作为实际 PRAGMA/identity 的期望值，不用于手造 v4。源码收集和实际 APK ZIP 同时要求这三份 asset 的原始字节等于对应真实 Room 导出，缺失、重复或不同字节均拒绝。不复制授权、提交或撤销业务逻辑。旧附件 v2 测试改用真正的 v2 加密夹具，修复此前从 v4 删除单表后仅设 version2 的错误夹具，当前终点为 v4。生产代码、依赖版本和锁文件不因此改变。

正式入口见 [D02_DEVICE_ACCEPTANCE.md](D02_DEVICE_ACCEPTANCE.md)。源码清单固定旧19+新8=27；实际存储 DEX 必须含这27项和独立重启探针1项，应用 DEX 另有独立 Activity 探针1项。未注册项目测试类、缺失/多余方法、重复方法均失败；manifest 还绑定完整源码 SHA、精确方法集合、APK 大小/SHA。每次 instrumentation 240s、整体2100s上限保持原值。源码收集和 Python 协议守卫不产生设备通过结论。

旧 `run-d02-kvm-ci.py` 与 `kvm-request-20261012.json` 绑定旧19项、固定旧 base 和一次历史许可，不是当前27项入口。其许可/变更范围不扩张，不重跑该历史设备任务；旧日志和授权记录保持原样。

新增测试的实际 Android 编译/DEX 结果仅以本次正常 push 后的同 SHA CI 为准，不复用 a8 的旧19项产物。没有受支持本地 Android classpath，因此本地 Android 编译 **NOT RUN**。交付记录应明确该新 CI 的实际状态，不将队列/编译成功表述为原生执行。

首次提交 `554f11282fd1027ef9ba2054f6f2370bafdf6329` 的 [CI 38418211606](https://github.com/T1doo/AIyou/actions/runs/38418211606)、job `115368228894` 实际 **FAIL** 于 `:core:data:compileDebugAndroidTestKotlin`。编译器报告3处混合 `String`/整数 SQL 参数的 `arrayOf` 推断出不可安全 reify 的 intersection 类型；静态审查未识别此错误。修正只在这3处显式写 `arrayOf<Any?>`，保持 SQL 参数值、测试方法和业务断言不变，不更新编译器/依赖。旧失败原始日志、首次404 BlobNotFound响应与最终job状态保存在 `.local-evidence/d04/room-native-first/`。job结束后原始日志正常可读；没有权限变更、备用访问路径或 CI rerun。修正通过正常开发分支提交后产生新 CI，结果另存且不得改写这次失败。

## 当前环境与最小恢复动作

2026-10-13 本地只读实查：无 SDK 包、既有 SDK 接受记录、adb、emulator 或 `/dev/kvm`；没有可见的已授权设备 serial。已锁定 JDK17/Gradle 的 JVM 能力可用。现有 CI 是构建服务，不是持续连接的设备服务。

本轮一次创建只读进程收到 `exec-server transport disconnected`，命令未执行；没有积压命令或增加写入者。随后2026-10-13 14:40:56 UTC实际 Git 状态命令成功，工作恢复，真实 schema 和已保存证据保全。

最小动作由平台在唯一已保存配置下恢复/接入已有许可 SDK 与已授权专用 API≥26 设备，或已有完整且已加速的受支持 AVD，先只读核验组件、许可状态与设备身份。当前不新增 SDK 协议接受、凭据、安全权限、挂载或目录访问；不启动 ADB/设备、不生成认证密钥、不重试旧无加速启动或已拒绝路径。不能靠更换 HOME/状态目录绕过旧 readonly `.android` 限制。

数据库撤销仍未跨连接广播给活跃 HTTP。最终拒绝保存与删除候选不等于立即停止已发送字节或预览；即时预览停止与 App 生命周期接线继续 **OPEN**。
