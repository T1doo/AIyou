# 可恢复回收站：F02 / §10.6 有限切片

此片实现用户明确移入和恢复当前原始记录，遵循两份原始主方案。它不代表完整 D08、
永久删除、派生记忆图清理或加密备份恢复已经交付。原生生产重启仍须设备验收。

## 用户行为

在原文详情选择“移入回收站”，确认目标日期、版本和原文节选及影响范围后才执行。
取消确认不改变记录。事务确认前保持原列表；持久化并读回后从当前列表、本地检索、
新导出和 AI 使用范围排除。用户可在回忆的“回收站”查看完整当前原文、手工标签，
以及保留的原文版本和附件记录数量。数量包括待确认附件，不等于附件全部可查看。

恢复需要针对当前查看记录再次明确确认；恢复后回到本机列表、本地检索和新导出选择。
恢复保持 `AI_NONE`，不会重建旧服务连接、旧同意或旧 AI 结果。以后使用 AI 需要重新
明确授权。此次实现没有新增真实 AI 入口、网络权限或模型调用。

保留所有不可变原文版本、手工标签密文、附件行和加密附件文件，以及已保存的加密
编辑草稿。移入时隐藏该原文的编辑草稿入口，恢复后同进程再次可发现。活动编辑、
历史查看或附件操作期间不接受移入；用户需先明确处理当前操作。独立手工待办保持
标题、版本与完成/取消状态；本片没有系统提醒调度实现。

已导出、另存或送给服务商的数据无法由回收站操作撤回。已经开始或完成的导出文件
遵循现有不可变快照/部分文件合同；这里不自动删除外部文件。用户操作排在同一个
本机 IO 队列，已进行的导出完成或取消后才执行排队的回收站事务。

没有永久删除、自动清空或新的 Android 权限，也没有破坏性数据库重建。

## 持久化与并发

`EntryRepository.recycleBin` 是同一加密数据库的借用能力，使用既有 counted SQL
gateway 和标签 AEAD；owner 关闭先封闭并排空 SQL，再关闭数据库并擦除借用密钥。
沿用 Room v6 的 `remote_source_access.deleted/generation` 和 `local_jobs`，无 schema
升级，未改变原始设计文档、旧 schema、SDK、依赖锁或权限。

每次操作绑定 source ID、原文版本、期望 visibility generation、期望 deleted 状态
和稳定 mutation UUID。同一事务核对这些值、推进 generation、设置 `AI_NONE`、清空
connection/consent、取消 QUEUED/ADMITTED AI 作业并清除本片现有 text annotations。
取消仅针对该记录的非终态 `LOCAL_INDEX`；恢复当前原文版本的既有取消索引作业为
PENDING，保留历史索引取消状态，不声称索引已经执行。编辑提交回执和独立待办不改。

操作回执写在独立 `RECYCLE:` 命名空间，包含记录、原文版本、方向、期望/目标代次。
只有回执完全匹配且当前状态仍为它的目标状态、`AI_NONE`、无远端授权时才能精确重试。
旧移入无法在恢复后重放，旧恢复无法在再次移入后重放。代次溢出失败并回滚。

写入或读回失败保留同一操作身份，只显示“结果尚未确认”，不假称操作成功或未发生。
安全读回不能完成时停用相关可见数据；读回已证明移入的源立即退役旧 AI/导出 owner，
旧标签检索选项也失效。返回/关闭不能撤销已经提交的事务。

界面会话、确认 ID、被查看记录和持久化版本共同约束回调。关闭、换页、换目标或
ViewModel dispose 后的旧回调不会写新目标。正文、标签、操作 UUID和确认内容不进入
SavedState、日志、导航参数或网络。导航只保留既有不透明记录 ID。

导出快照在同一 capture 事务内额外读取 source generation，并参加 `isCurrent` 比较；
它不扩展 JSONL/Markdown 的用户数据字段。移入再恢复同正文/同标签也不会复活旧导出
确认或目的地回调。已提交到服务商的请求不能承诺远程撤回；旧返回仍受 durable CAS 拦截。

## 检查合同

- `RecycleBinSqlTest`：真实 host SQLite 与两独立 PID；移入/冷重开/恢复，全部版本、
  标签密文、附件与独立任务保留；实际附件 AEAD 文件字节与读回；回滚/lost ACK；精确
  replay、并发 CAS、源变更、代次溢出；当前 AI authority 拒绝旧 job/结果；导出 generation
  ABA；坏 hash/标签密文失败不重建；借用 owner 关闭排空。
- `RecycleBinControllerTest`：实际产品 controller 和 VM IO 队列；确认/取消/重复动作，
  迟到读取与 CAS、跨目标 restore、失败稳定重试；旧导出 UUID opens=0，已导出文件不删；
  host VM 冷重开；真实加密编辑草稿同 owner 恢复后可发现且文件不改。
- `RecycleBinPresentationTest`：实际 Compose/navigation/controller，synthetic memory ports；
  明确移入/恢复、全原文与标签、失败/空白/加载区分，320dp/2倍字号、失效内容隐藏，
  重组前旧按钮/确认/跨目标回调及 raw SavedState 检查。SQL 证明由前两组另行提供。

host SQLite 不证明 Android Room/SQLCipher 原生执行；JCE 文件测试不证明 Android
Keystore 或 PNG 解码；Compose JVM 不证明真机触感、TalkBack、OEM 行为或生产进程重启。
既有 35 项原生方法和 2 项外部探针只编译，设备执行仍 **NOT RUN**；本片未新增或运行
设备/ADB/模拟器命令。原生 SAF、完整 D02、完整 D08 与加密备份/恢复仍 **OPEN**。

交付以最终完整 SHA 的实际 CI/测试 XML、原始 APK 报告、远端引用及干净工作树为证据；
重复 CI 不累计测试数，APK 每次原始哈希单独记录，不把 artifact ZIP digest 当 APK hash，
也不据不同哈希推断二进制差异原因。
