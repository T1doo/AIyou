# D07 R2b 原生提醒接线：精确提交 CI 封存

父侧已对产品 `52f36c0489f9ca3d767b3c51d3e42cf21b174f96` 的四项 lint 修正给出限定 ACK，
并复核消费者包、目标 blob、大小及 SHA256。随后正常快进推送已审交付提交
`488f87cef8247b1943fee38d2ac0f9854361f574`，没有改 main、强推或重复派发。

[CI 39449247408](https://github.com/T1doo/AIyou/actions/runs/39449247408) 为该精确 SHA 的 push 自动运行、attempt 1，
2026-10-20 15:31:46 UTC 开始，16:03:01 UTC 更新为 **completed / success**。
本文件只封存这轮工程验证；[原生设计](D07_R2B_NATIVE_WIRING_DESIGN.md)、[实现及局限](D07_R2B_IMPLEMENTATION.md)仍适用。
[前一轮失败及修正候选](D07_R2B_CI_LINT_FIX_REVIEW.md)保留原时点记录，不借用旧绿灯。

## 本轮实际结果

| 实际执行范围 | 结果 |
| --- | --- |
| Android Room v15 KSP、提交/打包合同、前代 schema 保全 | PASS |
| AI / domain / remote-text JVM，方法/XML/输入快照门 | PASS：178 / 71 / 1082，合计 1331 |
| host queue / Python / tokens | PASS：188 / 168 / 一致 |
| 受控原生提醒全链，真实生产副本及 XML 清单 | PASS：41；平台端口受控，不能当设备执行 |
| native observer / component / pre-open 证据 | PASS：8 / 7 / 21 |
| `:app:assembleDebug` / 编译 APK 权限审计 | PASS；无 INTERNET、无 USE_EXACT_ALARM |
| Compose JVM interaction 全量及同轮子集证据 | PASS：304；子集不重复计数 |
| instrumentation APK 编译、DEX 方法清单、许可资产及 ZIP alignment 静态检查 | PASS；没有执行 instrumentation |
| `:app:lintDebug :core:designsystem:lintDebug :core:data:lintDebug` | PASS：app 0 errors、0 warnings、1 hint；另两模块 No issues found |
| Preview 生成、验证及非空完整性门 | PASS：394 tests、394 PNGs，failures/errors/skipped 均 0 |
| PNG 包、索引、schema、报告、APK 及 Preview 保存步骤 | PASS |
| 旧 lazy-node 专用独立复现 | SKIP：提交无 `[ui-lazy-repro]` 标记；不计作 PASS |
| 真实权限/通知/闹钟/点击/重启/首解锁/OEM/SQLCipher/16 KB/性能、手动视觉验收 | NOT RUN |

全部 JVM 计数来自本轮实际日志及已通过的方法/XML/输入快照检查；domain 的独立报告属于上述 71 项，不另加总。
原生输入指纹为 `09aae81dab1565466f5dba8d0172b8463617c2e0e83fd595bc360b7749f771b3`；
host 输入指纹为 `a2d737c21cd45b1f651633de5a3f288b6b747635b60bc26acbbf893915203254`。
原有 MainActivity:229 `AutoboxingStateCreation` 仅 hint，保持原样；没有关闭 lint 规则或增加 baseline/suppress。

## 可追溯产物与日志

CI debug APK 路径：`app/build/outputs/apk/debug/app-debug.apk`，23608220 字节，
日志中的 APK SHA256 为 `e7ffcc67db4b9b99cb245dfb77da26530262bb2a4d9ea5aa86d9756509462d53`。
本轮 `aiyou-d01-debug` artifact ID `12278539711`，归档大小 19446904 字节，服务端报告 digest
`sha256:c8d9239cb5233be52a0f38c8fbf6ea30fc5d80d219a86ed15341c2882b57eb1f`。
该归档 digest 与 APK digest 分别记录。

16 项 artifact 元数据已逐项核对：同一 run/head SHA、非空、未过期、digest 格式有效。
没有下载 artifact/APK 二进制或本地重算远端归档；原 Azure 拒绝资源没有重试或绕路。
本轮普通 job 日志接口成功返回解码文本，UTF-8 原始日志 1336880 字节，SHA256
`4cfddf9c5ab7ff7768f5ee7ef89b54b198bc76cc2ff6f41bd95d5098e7f44226`；JSON 源与文本已逐字节核对。

[本轮证据 ZIP](evidence/d07-r2b-ci-488f87c-closure-20261020.zip)：363672 字节、30 项，SHA256
`fe155c2054606aeaa44e0edcd68ba065d082a797941c37978f4e9e1b4876a442`。
含原始日志/原文 JSON、终态与步骤、产物元数据、实际计数、观察快照及逐文件 MANIFEST；CRC、大小和逐项 SHA256 已核。

## 交付边界

封存前本地及远端开发分支均为上述测试 SHA，工作区干净、ahead/behind 0/0；main 保持
`7383e502418f5f9fc0c540ef2072742359343d40`。后续封存提交只更改 docs/evidence，使用 `[skip ci]`，
必须分别报告测试 SHA 与最终文档交付 SHA，不能把文档提交称为另一次实际 CI。
两份原始产品文档 SHA256 保持不变。

本片关闭已审 R2b 实现的这轮 lint/APK/Compose/Preview CI 验证；D07 整体验收仍 OPEN，D03 ASR/OCR、D04 绑定也不因此完成。
设备权限与系统调度、冷库/进程死亡和重启等平台效果仍需按原设计单独取得设备证据；目前不执行真实权限或通知。
继续保持原生 Kotlin/Compose、LOCAL_ONLY、无自建后台/真实模型调用/新费用/凭据/SDK 条款接受，未部署或上架。
