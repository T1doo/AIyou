# F08：活动开放导出的最小闭合

2026-10-16，当前新环境从默认 `work/7383e50` 检查文件和 Git，默认网络
`git ls-remote` / `git fetch` 成功；核对目标远端
`codex/f08-open-task-proposal-export-20261016` 精确为
`e0c4e93184e2df3e0ff843cda70c7bc90df7720c`，从该提交建立
`codex/f08-export-limit-saf-closure-20261016`。不恢复旧实例写入。
已读 AGENTS、原 Master Plan 和 Architecture DOCX，原设计文件保持不改。

## 本批范围

正式待办超过 256、已保存 DRAFT 超过 256、提案物理台账超过 4096，分别返回
明确的类型化限额错误，不再包装为一般 StorageFailure。读取仍用 257 / 4097
探测并整体拒绝；不截断、不跳过损坏行、不改变 256-DRAFT 创建门。UI 说明
具体限制，提示关闭本次后取消活动范围并选择原文单独导出；不要求用户删除
任务或历史来避限。写入后完整读回期间若越界，也保留限额错误而不显示成功；
已选文件保留、提示检查或另行明确删除。损坏/未知读取继续走存储失败。

新增主机边界覆盖 256/257 正式待办、256/257 已认证 DRAFT、4096/4097 已认证
拒绝历史；核对拒绝前后数据库不变。另用实际 SQL/controller/codec/文档 IO
验证活动范围下 Markdown 和 JSONL 的取消→重新确认→新 UUID、旧 key 迟到、
重复 picked 只入队和写读一次，以及写入期间新增第 257 条后的准确失败。

Compose 活动专项增加实际 App/dialog/controller/ActivityResultRegistry 与
ContentResolver/合成 provider 文件回归，验证取消后重新确认、JSONL 重选、旧
registry code 迟到、重复结果只写读一次，并加强 Markdown→JSONL→Markdown
后旧 epoch 不恢复明文确认。新增方法进入强制 XML 方法清单。本机没有 SDK，
这些 Compose 源码本地 NOT RUN，须由本批精确 CI 验证；没有改 golden 或跳测。

README 同步已有混合输入、待办时间/DRAFT、图文 ZIP、活动 Markdown/JSONL、
有限加密新副本恢复范围。未把这些切片描述为完整 F08/D08 或真机迁移完成。

## 证据来源与保全

当前环境没有旧 `/workspace/aiyou-evidence/f08-open-task-proposal-export-20261016/`
原件或旧工具链；不假设跨环境共享，也未恢复旧实例。上一批 716 JVM / 149
队列 / 123 Python 在父级只有作者报告，此处不当作新批验证。

父级提供的精确基线 CI 原始日志核实：
[run 38889673687 / job 116935838555](https://github.com/T1doo/AIyou/actions/runs/38889673687/job/116935838555)
于 11:10:36 UTC 成功，45 步全部通过；Compose 212、失败/错误/跳过均 0，
两个 OriginalActivityExportPresentationTest 方法各 actual 1 通过；Preview
394 tests/PNGs、lint 0 错误/0 警告/1 hint、APK/instrumentation 编译通过。
来源是父级原日志核实，非本机重新抓取、XML 重数、视觉或真机证据；不覆盖本批。
本任务不查询曾拒绝的 Actions，也不从其他路径绕过获取日志。

新环境仅按仓库脚本下载并校验固定 Temurin 17.0.17+10、Wrapper 和主机依赖，
没有安装 SDK 或接受新条款。证据保存在
`/workspace/aiyou-evidence/f08-export-closure-20261016/`，包含原始命令日志、
实际 XML、执行前输入指纹、来源清单与独审结果；首次依赖准备/中间运行保留，
最终结果另列，不能将中间 XML 与最终来源混用。

真机 SAF、全新安装→恢复到原文和附件→重连密钥→提醒原生演练仍为 NOT RUN；
旧 94/96 SQLite 初始化/退出超时根因未知。仅合成 LOCAL_ONLY/AI_NONE 数据，
BYOK 无后台边界保持；无真实模型、SDK 接受、权限/凭据改动、部署或费用。
本机 SDK 预检失败；AAR metadata、APK、lint、Compose/Preview、instrumentation
均未运行，本批没有本地 APK。正常开发分支推送仅触发现有有界 CI，不另 dispatch。

## 最终适用检查

- PASS：`python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --offline --rerun-tasks`，720/720（AI 178、domain 46、SQL/VM 496），失败/错误/跳过均 0。活动专项实际 14/14，包含本批 4 项新增方法。
- PASS：`python3 scripts/verify-ai-protocol-results.py`，源码方法清单与实际 XML 精确一致，输入 SHA-256 `630d1d32e66eda54474d41d6e1cb02903e25177c1771c7f42af87cd7bf40c4f2`。队列执行前已保存该轮 XML 和执行前输入记录，队列后再次核验同一指纹。
- PASS：`python3 scripts/jvm-gradle.py -p checks/record-queue test --offline --rerun-tasks`，149/149；`python3 scripts/verify-test-results.py --queue` 核实实际 XML，失败/错误/跳过均 0。
- PASS：`python3 -m unittest discover -s scripts/tests -v`，123/123；Token、HTTP 依赖校验与 `git diff --check` 通过。独审发现的写后限额吞并已修复并执行新增回归；修正后只读复核无未修阻断。
- FAIL（环境预检）：`python3 scripts/check-sdk.py`，缺开发者已接受许可的 SDK；没有接受条款或安装。原始日志保留。
- NOT RUN：本机 AAR metadata、APK、lint、Compose/Preview、instrumentation 和上述全部原生/真机门。新增 1 个 Compose 方法及加强的旧方法尚无本批执行证据；已加入既有 CI 的实际 XML 强制清单。

`host-initial.log` 与 `host-initial/` 是独审修正前的中间 719 项成功运行，不作为最终
源码验收；`host-final.log`、`host-final/` 和 `queue-final/` 为最终原件。
来源说明在 `provenance-initial.json`，最终汇总在 `final-results.json`，逐文件
SHA-256 和交付 Git 核对在证据目录内。旧环境原件缺口保留，不以新测覆盖旧失败。
本批正常推送后的精确 CI 状态为 UNKNOWN；没有查询 Actions，也未借用基线 GREEN。

## 后续精确 CI 失败与 launch 同步修复

父级独立回传已读原日志：`6f68806fb605d7ecf789989ca1721f2d22f07e8c` 的
[run 38895472312 / job 116954786680](https://github.com/T1doo/AIyou/actions/runs/38895472312/job/116954786680)
于 2026-10-16 11:59:51 UTC 失败。Compose 213 项中 1 项失败，新增
`activitySafCancelReselectRetiresOldUuidAndDuplicateResultWritesAndReadsOnlyOnce`
在原第 128 行 `registry.codes.single()` 抛出 `NoSuchElementException: List is empty`。
此前已读到 requestKey；取消、重选、旧 UUID、重复回调和一次写读断言尚未达到，
不能将这些后续断言计为 CI 通过。APK 通过；instrumentation 编译与 lint 跳过。
这取代上节交付时的 CI UNKNOWN，不取代主机原始 XML，也不关联旧环境附件缺口。
本机没有抓取该 CI 原始日志或 XML；精确失败来源是父级日志核实，不绕 Actions 拒绝。

源码检查发现：`pump()` 在 `runOnIdle` 回调内排空 IO 队列并发布 `PICKING`，
回调返回不构成这次状态变化后的新 idle 屏障。真实注册和 launch 仍在生产
`OriginalExportPicker` 的 `DisposableEffect` / `LaunchedEffect` 中；读取 requestKey
本身不能证明 registry 已收到 launch。旧已通过方法在 pump 后第二个 runOnIdle
中观察 intent/code。本次同步诊断与失败位置一致，但没有本机 Compose 重现证据，
不由静态线索断言生产必然没有缺陷。

最小修复只调整该新增测试：两次 pump 后均在第二个 `compose.runOnIdle` 中
同时检查 PICKING、pickerLaunched、准确的 registry codes/intents 数量和真实
ACTION_CREATE_DOCUMENT/MIME，再读取 key/code；没有直接调用 registry.onLaunch
或绕过生产 picker。保留取消后撤回确认、重新确认、不同 UUID、旧 code 不写入、
重复结果只入队一次、实际 provider 一次 wt/r、完整分类正文、零自动删除及输入/
任务/DRAFT 不变的全部断言。未改 pump、生产代码、必需方法清单或质量门，未加 sleep。

本机 SDK 前置仍失败，新 Compose 方法修复后的执行仍为 NOT RUN。生产输入指纹
与上节相同，现有 720 项原始主机 XML 再核验一致，只证明相关主机输入未改；
不计作新 Compose 执行。可用本地检查、父级失败来源和独审结果分别归档到
`/workspace/aiyou-evidence/f08-saf-launch-ci-fix-20261016/`，旧证据目录保留。
本修复正常推送后的精确 CI 门仍待新 SHA 执行证据；不能据静态修复宣告关闭。

修复批本地结果：Python 123/123、三项 Compose 源码方法与必需清单静态比对、
`git diff --check` 均 PASS；720 项旧主机 XML/输入指纹再核验 PASS，明确为复用证据。
SDK 前置 FAIL，修复后的 Compose/Android 编译执行 NOT RUN；不计为本批通过。

父级后续独立核实：修复提交 `7e8c2168e524ce4123046739727e6eafb32ac881` 的
[run 38901288626 / job 116973907479](https://github.com/T1doo/AIyou/actions/runs/38901288626/job/116973907479)
于 12:49:32 UTC 完成，45 步成功；Compose 213 项零失败/错误/跳过，失败的 SAF
方法实际 1 次通过；Preview 394 tests/394 PNG、lint 0 错误/0 警告/1 hint、APK
及 instrumentation 编译通过。此前 6f68806 失败记录继续保留。该来源只接受为
父级已读日志核实的 JVM 模拟与编译门，不是真机、视觉或对另存 host 归档的独立
核验；本机未查询 Actions。新 F06/D07 提醒模型另按新源码执行及独立 CI 验证。
