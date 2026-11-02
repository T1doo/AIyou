# 待父核准的一次编译 / lint / 定向回归（历史提案）

后续状态：此原方案获批准后在run5发生零job的context校验失败；父再精确批准环境修正及run6，run6已success。此文件保留原提案/hash与当时预算边界，不作为新的执行授权；正式结果见docs/evidence/frontend-day-run6-20261102.json及20261102-run5-startup-failure.md。

状态：仅准备与只读核验。真实 `.github/workflows/frontend-day.yml` 仍保留字面量 `false` 硬冻结；本目录不在 Actions 执行路径内，未创建 run、上传产物、写远端缓存或发布 APK/package。

本次产品源固定为 `908ad0023dd11a2c972d59204487787a2500eb30`。它直接继承 `2f7b4c4c1f635af4528f1b99823e944268b71bf7`，后者继承最新 Android 通过源 `cf54f3d9b4781a2fb4402ef86a4aeee8ee8bd482`。908ad00 增加独立筛选面、真实侧边操作带与回答复制；主机11套件105项通过，但尚未 Android 类型编译/lint/新截图验收。

## 已只读核实

- GitHub REST `GET /repos/T1doo/AIyou`：`private=false`、`visibility=public`、`default_branch=main`、未归档/禁用。未使用新凭据或 OAuth。
- 标准 label `ubuntu-24.04`，x64、4 CPU、16 GB RAM、14 GB SSD；不用 larger、自托管或付费 runner。GitHub 官方：[公开标准 runner 免费](https://docs.github.com/en/actions/concepts/billing-and-usage)、[型号和公开仓库规格](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)。执行前与 job 开始时再核公开性；核验失败即停。
- 已有 workflow ID `404271199`，当前累计4个 workflow run，最新 `run_number=4` / `run_attempt=1` / `41415468944` / `cf54f3d…`。项目总账则为17次／7h16m33；两个计数口径不混用。
- main 中 `.github/workflows/frontend-day.yml` 的正式 Contents API 返回404。GitHub [手动运行规定](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)要求 workflow 位于默认分支，因此不能以当前 dispatch 方案直接执行，也不为此修改 main。
- 现有 setup-java 未配置 `cache`；项目未配置 remote buildCache 或发布任务。候选的每条 Gradle 命令进一步显式 `--no-build-cache --no-configuration-cache --no-scan`，Gradle/Android user 目录仅置于临时 runner。仅沿现有 pinned checkout/setup-java、wrapper、JDK17与已锁定 Maven/Google/Plugin Portal 依赖来源；不接受新 SDK 协议、不增加凭据/安全权限。

## 精确可审阅改动

- 完整候选：`20261102-compile-lint-no-upload.candidate.yml`
- 作用于真实 `.github/workflows/frontend-day.yml` 的未应用补丁：`20261102-compile-lint-no-upload.patch`
- 候选 SHA256：`1493ef6f46bf5c3452dbb8bd62d31ee6db144c2ffb18513ceeb027810c36746b`
- 补丁 SHA256：`5a28868964d152b8739fc68b294c87fde99a4e8834f5472df40b3997c26af906`

补丁删除所有 dispatch 输入（包括未批准的8MiB/1天上传开关）、Preview和重复基线、ZIP收集与 upload-artifact 步骤。没有 cache action、package/release/deploy、矩阵或自动重试。

仅在父批准后，把该补丁作为一次仅 workflow 的正常开发分支提交，以**精确消息** `[frontend-once-no-upload] 908ad0023dd11a2c972d59204487787a2500eb30` 推到 `frontend/day-20261102`。job同时限定该分支、仓库公开、该消息、`run_number == 5` 与 `run_attempt == 1`，其余事件不执行 job。checkout 固定908ad00，正常日志分别打印实际 workflow 执行SHA和真正编译 source SHA，避免把提交关系混为一谈。

在批准后应用前，必须再只读核最新workflow编号仍4、无在途run、源与候选hash未变、仓库仍公开；若条件变化先报告，不重置计数或自行扩大条件。批准只覆盖一次，不失败连跑、不修改断言/无限超时，后续修复可本地做，重跑另报。

## 执行范围与上限

单标准Ubuntu job，`timeout-minutes: 20`（含checkout/JDK/依赖获取）。子命令12分钟构建/lint、3分钟host、2分钟Android定向测试，均TERM后10秒KILL；全局20分钟先到即取消。setup及冷依赖仍计入全局上限，不保证全部步骤赶完；未到达的检查按未运行报告。

| 检查 | 精确范围 | 预期实际测试数 |
|---|---|---:|
| SDK/源/token/schema | 只复用已接受协议SDK；精确source/hash、385463祖先、5项token契约、所有原schema guards | 5项Python契约 |
| 原生构建/lint | `:app:checkDebugAarMetadata :app:assembleDebug :core:domain:test :app:lintDebug`、编译APK无INTERNET核验 | 领域12套件78项 |
| host协议 | candidate中显式11个 `--tests` 选择器：草稿/Undo、图文、图片proof、音频、任务VM/回执/来源/跨密钥备份、本地查找、当前回答/回答历史复制 | 11套件105项 |
| Android定向回归 | `UiPreferencesTest`、`MixedCapturePresentationTest.frontendDayFocusKeepsDraftAndFailedSaveRetriesTheSameRecord` | 2套件12项 |
| 实际结果 | XML存在/套件与用例数均须符合，失败/错误/跳过均0；仅stdout打印计数，不上传XML | 合计25套件195项JUnit |

`assembleDebug`生成的APK、lint报告、测试XML、schema检查记录和依赖缓存均只留临时runner；不调用发布/上传任务，job结束随runner回收。保留assemble仅用于真实编译与既有APK权限检查，不是APK发布。

明确不运行旧问题回填 ShadowTrace OOM 组、完整app UI套件、Preview截图、设备/emulator、D08、TalkBack、真实IME、模型下载、OCR/ASR、真实AI或联网。不能用cf54截图认证908ad00。

## 继承的账与未批准项

原账17次／7h16m33（26193秒）；若这一次获批且执行到配置上限，计划总账至多18次／7h36m33（27393秒），完毕按API实际job起止补账，不归零。公开标准runner计算免费依据如上；本方案没有新增artifact/cache/package写入。已有四工件合计76,949,894 B、7天保留不动；其既有账户存储费用并未核定，不能称账户storage零费。最小8MiB/1天上传方案仍未批准，也未包含在此候选中。

本地只核验YAML、精确SHA/消息/编号/attempt/20min/权限、既有action pins、缓存关闭与无上传/发布路径；没有通过试跑检验方案。父核准前不应用真实workflow、不提交触发消息、不执行。
