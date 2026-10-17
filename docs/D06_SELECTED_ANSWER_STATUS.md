# §5.2 / D06 选定出处的合成回答切片

基线 `700d6ab78a13f20446b6ebca9a2fe75a4b3f9d42` 已获上层独立源码/运行证据签核。
本片仍由同一指定环境中的唯一写入者执行，分支 `codex/d06-selected-answer-20261017`；
已核干净工作区、远端 HEAD 及祖先、正常 Git/default 网络，两份原方案哈希不变。
未知 `.git/codex-index-refresh.lock` 保留；未发现其他本地 SKILL.md。

## 单一范围及现状核对

已存在有界词法候选、全部组合筛选、原文导航和本机选择确认，没有基于该选择生成
分段回答及逐段来源的流程。人物关联仍缺失，日期/标签/收藏/图片组合已实现。
本轮选择可在主机独立检查的 D06 合成回答核心与现有界面接线；不并行做人物实体、
持久化或迁移。Android 界面验收仍依赖未批准/未配置的 SDK，不把主机流程称为 Android
完整验收，也不把任何迁移门放宽。完整产品 D06 及真实多源问答继续 OPEN。

## 先修 inspection 提交边界

旧实现在 `publishCurrent` 的事务回调里交出 packet，随后 COMMIT 失败时，返回 false
及清空确认无法撤回已交的数据。本片将确认和 inspection 都改为两阶段：

1. 只在 SQL 回调内检查并标记资格，packet/receipt 不在此时交付；等待整个事务成功返回。
2. 提交成功后，再检查控制器/原问题/选择身份、receipt 对象及取消信号，才执行纯内存消费。
   `false` 始终表示消费者从未调用。消费者若抛异常，异常向上传递，不将已发生消费
   伪装成 false 拒绝；没有自动重试。

这是有明确线性化点的本机读取，不是网络/文件副作用权限。SQL 提交后再发生的撤权
不能收回已经读过的字节；当前接口绝不能直接承接网络、文件或其他外部副作用。
未来真实发送需要独立的持久多源逐源授权、admission、取消/订阅和提交协议。
新增 inspection 后 COMMIT 失败、COMMIT 边界取消/清空、消费发生在 COMMIT 后以及
消费者异常语义三项回归。该边界阶段专项 19/19 PASS，原始 XML/日志单独保留。

## 选定出处到回答再到原文

复用原 `LocalEvidenceController`、selection/receipt 与私有 proof，不建立另一层候选
或确认包装，不重取候选/排序。新 `LocalEvidenceAnswerController` 只管理回答运行。
用户使用原卡选择并确认后，可在注入合成 fixture 的验证界面点击“基于选定出处回答”，
查看完整回答段落和每段来源卡，点击走既有 search.open/resolve 原文定位。停止按钮
只取消本次请求；不把未完成文字当作回答。返回、切换选择或来源需重新确认。

- 请求有独立 UUID，绑定原 selection ID、同一个 owner/proof、问题和逐源 current
  revision/原文哈希/access generation/策略。request 中只有原 packet 的选定引文、
  日期/时区、版本及策略，不含全原文、未选候选、连接、密钥、consent 或发送能力。
- provider 仅为 `SyntheticEvidenceAnswerProvider`，合同限纯内存合成资料。真实 Activity
  使用默认 null，没有注入实现，普通产品只提示回答尚未接入、仍可核对原文。
  全仓无真实多源发送实现或从本机确认到 wire request 的转换器。
- provider 在 preparation COMMIT 成功后、SQL/控制器锁之外执行。本地 handoff 后取消
  会设置 request.cancelled 并取消 coroutine；已经开始的非协作合成处理可能结束，
  但其结果不能发布。取消不是撤回已读数据，不对未来远端调用作费用/召回保证。
- 每个回答段落都必须引用本次所选的精确 `OriginalTextReference`；ID、revision、码点
  范围和 quote 必须完整匹配。未知/未选来源、旧版本、改造引文、错误 request/selection
  ID、重复引用、空引用和无效 Unicode 均使整个结果失败，不显示部分结果。
- 输入仍是原候选的字面问题/关键词/精确片段，未新增自然语言召回。输入预算沿用
  最多五来源、问题512码点/2048字节、引文1200码点/4800字节，合计1712/6848。
  完整输出最多五段、合计1200码点/4800 UTF-8字节、五处引用；不是 token/堆保证。
- provider 完成只是临时输出；完整校验后再次通过原私有 proof，等待结果检查事务
  COMMIT 成功，最后重查运行/选择/取消身份才发布可见回答。读取或提交失败清空旧确认，
  源删除/改版/撤权恢复 ABA 不得让迟到结果回填。源/输入/账号/路由变化可立即隐藏
  旧回答，sync 会取消运行，原文打开还必须通过既有 SQL resolve 的当前源检查。
- 没有可回答证据使用明确 NO_EVIDENCE 状态，不附带编造文字或来源，不据此推断事情
  从未发生。未知引用/失败、取消、过期、无证据与未接入分别显示。没有流式半成品、
  自动修复或自动重试。引用有效不证明自然语言论断获支持，仍需人工核对和后续质量门。
- 所有新问题/回答/request/身份只在内存；无数据库、Bundle、DataStore、文件缓存或
  永久事实写入。原存档保持独立，模型输出没有任务执行权。

`SourceBoundTextJob`、`CurrentSendAuthority` 和实际单源协议/传输边界均未改。
本机确认不是 ConsentGrant，单源许可仍不能覆盖另一来源，LOCAL_ONLY 不可外发。
没有新增 schema、依赖、权限、条款、凭据、费用或真实模型调用；没有 main/强推/部署。

## 验证范围

修复后候选/确认19项与新回答18项专项 **37/37 PASS**。回答专项执行实际控制器、
matcher、生产 SQL/私有 proof 和有界 host SQLite transport；合成 provider 是内存
记录器。包含主机端用户确认→请求→逐段引用→SQL 原文 resolve，且原存档无修改。
这不是 HTTP、真实模型、Room11、SQLCipher 或 Android 平台证据。

新增三项实际 Compose 源码用例登记原精确门禁，尚未编译/运行。SDK只读前置本轮
FAIL：`ANDROID_HOME must point to an SDK with developer-accepted licenses.`
AAR/APK/lint、Compose、Room11/SQLCipher、Preview、真机、返回像素位置、字体/TalkBack
及性能均 NOT RUN，没有新 APK。SDK/通知权限仍未批准，没有接受或配置。

完整回归与原始证据/输入指纹见 [本轮主机证据](evidence/D06_SELECTED_ANSWER_HOST.md)。
新切片独立签核交上层复审，既有签核不自动扩展到本片。

## CI 已知质量门事实

上层根据 GitHub 原通知邮件核验后转交；当前环境没有访问 Actions/日志/artifact，
也未尝试替代路径。以下都只有通知及 annotations 数，没有具体步骤/失败原因：

| 提交 | D01 run | 结束 UTC | 结论 |
| --- | --- | --- | --- |
| 01584dd7df71d9ffa3754ffa2555fe4580c6abc0 | 38980866184 | 23:43:47 | FAIL，4 annotations，原因未知 |
| cd7108e06a96650f64da135a69e78f267d4c7b59 | 38985766374 | 00:43:08 | FAIL，4 annotations，原因未知 |
| fa42ab07f62af29ee8c22700b388e3c0a8073d61 | 38987087195 | 00:51:02 | FAIL，4 annotations，原因未知 |

4785176f900f90c428a03e7477fdf4d05e70c459、700d6ab78a13f20446b6ebca9a2fe75a4b3f9d42
及本轮新提交的 CI 均未知。主机有限验证与上述 CI 失败并存，不能推断同根因或据此
盲改/重跑。没有将通知归因于旧 height 或分包问题。

最终实际 JVM **962/962 PASS**（AI178、domain71、SQL/controller713；新增21项已包含），
原始XML全部0 failure/error/skip；精确输入门及离线校验通过，源码指纹
`aff4f3593fe4c5db43194e5e33d3d78063e4aa14b8fce86b95a572fa60579b64`。
Python151本轮复验通过；队列149仅历史。执行通道一次exit回执与Gradle/shell成功/0
不一致，原始回执记录和日志已保留，962个原始方法独立核验，无推测性测试重跑。
