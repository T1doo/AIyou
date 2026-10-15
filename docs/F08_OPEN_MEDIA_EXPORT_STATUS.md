# F08 / §14.4 选定图文开放资料包

本批从实际 fetch 核验的 `d1231dc962940d39b95b9088b344c292a67c8683` 建立
`codex/f08-selected-media-export-20261015`。原始主计划与架构 DOCX 保持原样；
main 基线 `7383e502418f5f9fc0c540ef2072742359343d40` 不变。
只使用当前保存环境的文件与远端，不恢复旧任务，不使用旧本地路径或 bundle。

## 用户行为与边界

在既有“导出选定原文”对话框选择“图文 ZIP”，显式选择当前记录，预览后确认
明文、云盘、已有文件覆盖风险，再通过既有 SAF 创建文档。支持混合、纯图和
纯文字记录。图文范围切换会撤销旧快照和确认；Markdown / JSONL 原纯文字
格式及其写入语义保留。

预览显示来源版本、手工标签、正文哈希、每图 MIME / 字节数 / 哈希，并提供
逐张原图预览。全部图片先逐张进行 AEAD / 哈希认证与生产图片解码校验；
非必需缓存不落明文文件。原图字节和原始元数据原样导出，可能含拍摄位置。
需要保密时使用现有加密记录包。ZIP 是所选资料的开放副本，不是完整备份，
本批不新增 ZIP 恢复、全库迁移、服务端、权限、密钥或模型调用。

限制沿用 20 条记录、20 张图、单图 16 MiB、原图合计 64 MiB。正文范围沿用
250,000 UTF-16 单元，每份文本文件最多 2 MiB。整个 ZIP 上限为原图上限 +
两份文本上限 + 256 KiB。逐图认证并在使用后擦除字节；读回缓冲固定 8 KiB，
不在内存累积完整 ZIP，不生成明文临时 ZIP。

## 开放格式 v1

标准 UTF-8 ZIP，可用普通解压、Markdown、JSONL 与图片工具读取：

| 路径 | 内容 |
| --- | --- |
| `records.md` | 可阅读原文、来源元数据与指向 ZIP 内原图的相对图片链接 |
| `records.jsonl` | 每行一条选定记录，原文、版本、时间、正文哈希、手工标签及版本、图片映射 |
| `images/<attachment UUID>.png` 或 `.jpg` | 认证的原始字节；文件名不使用用户原文或外部路径 |
| `manifest.json` | 格式/schema 版本、快照 ID、创建时间、范围/排除项、记录/图片数量、文件清单及 SHA-256 |

`manifest.files` 包含两份文本及每张原图的精确字节数和 SHA-256；manifest
本身不自哈希，`manifestSelfHash=false` 显式说明。图片映射包含来源 ID / 版本、
附件 ID、MIME、路径、字节数和哈希。JSONL 保留精确原文；Markdown 使用可变
长度围栏防止原文中的链接、HTML 或反引号改变文档结构，仅生成的图片链接可用。
ZIP 无口令且 `encrypted=false`，不包含历史版本、任务、派生 AI 内容或密钥。

## 失败、取消与验证

任何缺图、非 READY 图片、AEAD / 哈希或解码失败、来源版本/标签/库存变化
都会让本次图文导出整体失败，不能成功导出一个悄悄少图的子集。预览、目的地
返回后、逐图写入及整文件读回后均重新核对来源。成功必须观察输出关闭确认、
整份 ZIP（包含中央目录）的精确字节数和 SHA-256 读回一致，再完成来源复核。

取消撤销 session / epoch / UUID 所有权；迟到结果不能恢复预览或写入新会话。
文件服务阻塞调用只能在返回后停止；不能承诺系统选择器或提供方具有事务性。
失败/取消会提示可能的空文件或残件，不自动删除或重选目的地；仅在用户另行
确认后删除本次选中文档。图片一旦可能发布给 Compose，交由正常生命周期回收，
避免立即取消时回收仍被前一帧持有的位图。

新增实际生产 SQL / AEAD / ZIP / controller / IO 契约用例，以及 Compose 纯图
预览、ZIP intent/明文确认、认证但不可解码 PNG 拒绝用例；已有纯文字专项保留。
只读独审与测试并行，独审指出的“认证不等于可解码”及位图发布/取消竞态均修复。

本批初轮合成图文专项有 1 个测试搭建失败：对已迁移 v9 的 `entries` 表误用
旧 fixture 的五列 `INSERT VALUES`，产生 `SQLITE_ERROR`；已改为调用生产 capture
接口建立纯文字记录，第二轮通过。它与旧两项 SQLite 超时不是同一失败，不能
据此宣称解决了历史超时。最后生产来源复核收紧后，又执行完整 JVM 套件；
来源指纹检查正确拒绝了部分 UP-TO-DATE 的旧输入凭据，因此显式重跑全套，
不通过手工修改凭据或忽略失败来取得通过。

当前最终生产代码全量 JVM 重跑：**625/625**，178 AI + 42 domain + 405 SQL /
controller / IO；0 failure / error / skip，包含本批 12 项图文专项，方法清单与
源指纹校验通过。源指纹：
`53d266420af8f820c3620e4635b5a8ffb27f32e103ce23d62a3b47835a4364a2`。
Python **123/123**，token 与依赖固定校验、`git diff --check` 通过。
现有实际 ViewModel/附件/编辑恢复队列回归 **148/148**，另执行 domain 42 项，
其 XML 非空且 0 failure / error / skip，逐项门禁通过。先保存前述 JVM XML
和源指纹收据，再运行共享 core 输出目录的队列工程。
实际生成的合成 ZIP 含 3 条记录、2 张原图，已用 `unzip -t`、Python `zipfile`、
`json`、`hashlib`、Pillow 完整性检查验证，未使用真实资料。

本地执行 `:app:checkDebugAarMetadata :app:assembleDebug :app:lintDebug
:app:testDebugUnitTest` 的命令在配置/依赖确定阶段失败，准确原因是
`SDK location not found`。这四项没有执行成功，2 项新增 Compose 用例仅已编写，
不能宣称通过；本批无可交付的新 APK。既有 workflow 的 25 分钟上限保持原样，
未新增 CI workflow 或 runner，未手动派发或重跑 CI；API Forbidden 时不捏造
CI run ID，也不能确认这次提交的 CI 结果。

执行状态及最终 SHA 以交付报告为准。当前环境 SDK 只读清单为空，无已接受许可、
adb、模拟器或 KVM；没有安装 SDK 或接受新协议。GitHub API 查询返回 Forbidden，
未尝试绕过；不能把历史 CI 或自动 push 触发当作本批成功 CI 的证据。

必须保留的历史事实：用户提供的旧中间本地测试为 **94/96**，两项图片包测试
涉及 SQLite 初始化 / 退出超时，原因未定。本批通过不抹去该历史失败；不能
推断已解决那两项历史超时。此前 `d1231dc…` 的 CI 38735069527 45/45 与
613 协议 / 148 队列 / 189 Compose / 123 Python / 394 Preview，以及 29 项
F09 专项是继承背景，本批没有把它们冒充为新提交的实测。

原生 SAF、Keystore、SQLCipher、设备重启/进程死亡、真机全新安装恢复、OEM
与 16 KiB 原生兼容验收均未执行；JVM 模拟、CI 和构建均不能替代真机验收。

## 2026-10-15 Compose 原图预览 CI 回归修正

从已核验的开发祖先 `84defbe52570faa5a0443b45a24da7003a8e47a5` 建立
`codex/fix-export-preview-graphics-20261015`。根据用户提供的 CI 摘要，
[run 38797060169 / job 116636123752](https://github.com/T1doo/AIyou/actions/runs/38797060169/job/116636123752)
在第 18 步 Compose JVM 回归中执行 191 项、失败 1 项：
`imageOnlyZipShowsActualAuthenticatedPictureAndRequiresConsentBeforeZipIntent`
在原第 80 行找不到 `export-image-total`。该次 CI 的 SDK preflight、debug APK
和无 INTERNET 权限检查已通过，后续 instrumentation / native / lint 跳过。
这是用户提供的结果，不是本地重跑，也不归因于本地 SDK 缺失；未取得原始
CI XML / 异常日志，未重试此前被拒绝的 Actions API 或另换路径取日志。

源码核对发现该类注入真实 `PlatformImageDecoder::thumbnail`，却缺少其他真实
图片测试的 Native 图形配置。固定 Robolectric 4.17 的
`GraphicsModeConfigurer` 默认 `LEGACY`；其 `ShadowBitmapFactory.decodeByteArray`
及所调用的 `create` 不填 `Options.outMimeType`。生产 PNG 解码器严格要求
`image/png`，异常会让控制器拒绝发布预览，随后图片统计节点自然不存在。
固定版本公开源码取自 Maven Central 的
[robolectric sources](https://repo.maven.apache.org/maven2/org/robolectric/robolectric/4.17/robolectric-4.17-sources.jar)
和 [shadows-framework sources](https://repo.maven.apache.org/maven2/org/robolectric/shadows-framework/4.17/shadows-framework-4.17-sources.jar)。
同步排空队列、纯图记录快照和版本匹配均未见缺陷。此为源码支持的根因链，
尚未通过本地 Robolectric 执行复现。

修复仅为测试类增加 `@GraphicsMode(NATIVE)`，与相邻图片测试保持一致；
生产代码、PNG MIME / CRC / 像素校验与导出策略保持原样。保留原节点、真实
图片显示、风险确认、ZIP Intent、损坏 PNG 拒绝断言；增加预览状态 / 无错误 /
图片数量及真实 1×1 像素 `0xff225599` 断言，以便区分解码失败和 UI 节点失败。
只读独审通过，无阻塞意见。

本次实际离线执行开放 ZIP 12、原文编码 4、原文 SQL 6、导出控制器 11 项，
合计 **33/33**；逐项核对 JUnit XML 与源码方法清单，0 failure / error / skip。
Python **123/123**，`git diff --check` 通过。未把祖先的全量 628 项和队列
148 项作为本次重跑。当前环境仍无 Android SDK / Robolectric 运行依赖；未
安装或接受 SDK 协议。因此修复后的 Compose、assembleDebug、AAR metadata、
lint 与原生验收均 **NOT RUN**，无本次新 APK，推送后的 CI 结果尚待验证。
旧 94/96 两项 SQLite 初始化 / 退出超时及 c43561f 的未知步骤 CI 失败仍保留，
本次结果不意味着它们已经解决。
