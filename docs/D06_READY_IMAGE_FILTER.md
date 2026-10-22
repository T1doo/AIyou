# D06 统一找回：当前修订就绪图片交集

基线 `5d5e377d41fc9f57470be2737c39902c5fb5f13a`；两份原方案 §5.2。
设计候选 `4fc1b2754491539105e9fada042b9d0c7f8b9bbe` 已独审；后续实施按本文合同接线。
本片不代表完整 D06、Android/Preview 或设备验收。

## 资格与用户闭环

- 新条件为“仅看当前有就绪图片的原件”，默认关闭；五类按**所属原件**共同筛选。
  关键词 AND 捕获日期 AND 标签 AND 人物标记 AND 收藏 AND 此条件，预算前取交集。
  这是图片附件资格，不是“图片文字”内容类型，不根据画面推断人物/内容，不要求 OCR。
- 仅 live 原件 `currentRevision` 的 PNG/JPEG、状态为 READY 且完成本轮认证的附件有资格；
  至少一张才匹配。仅历史修订有图、只有音频、WRITING/STAGED/FAILED/CORRUPT 不提供资格。
  混有合法非 READY 附件时不伪装成可读；其元数据仍验证，不读取预期未就绪的文件。
  两张 READY 中任一损坏时整组失败，不退化为“还有一张能用”或成功空集。
- 原文旧 `onlyImages` 明确包含异常关联，这条旧路径保持原语义；不能偷偷改成 READY-only。
  新 UI 说明“当前就绪图片 · 不识别画面”。切换推进请求并清结果/页码/旧错误，不自动查询；
  空白词不扫描全库，清空同时复位。图片-only 原文仍为空，不发明文字命中。
- 既有五类出处动作保持其精确原文/原音/原图语义，不任意换图或自动打开/识别图片。
  正常返回带条件重查第一页，不恢复旧 proof；条件/内容不入 Bundle/日志/明文索引。

## 完整认证、有界读取和提交

- 同一加密 records owner 注入只读 `OwnerAttachmentStore`；不用会 recover/标 CORRUPT 的读取路径。
  SQL 每批 20 个 live 来源，当前附件也按 ID keyset 每批至多 20 项；
  单源当前图片最多 64、以第 65 张判超限，不新增“图片+音频”总数上限；先检正文 byte length，
  再验真实正文 hash/revision/generation/捕获时间/合法保存时区/策略，以及所有当前图片的
  ID/entryId/revision/MIME/hash/byteSize/state。当前非图片附件也验证既有合法 metadata，
  未知 MIME/畸形行不能被 SQL 图片条件悄悄跳过；不读取音频本体或新增音频额度。
  源回收或任意永久删除阶段均排除，媒体空正文可用。
- 对所有合格来源的当前 READY 图片逐幅在 SQL 事务外调用现有只读 read：完整 AEAD、
  声明大小（≤16MiB）、SHA256 和 MIME 签名认证，finally 擦除明文。不解码缩略图、写临时文件，
  不把签名检查冒充 JPEG 像素解码；原图查看仍承担现有严格解码与显示错误。
  认证包含关键词/日期/标签/人物/收藏之外以及非命中来源，不借筛选跳过损坏。
- 流式元数据回执含全部来源/当前图片（含零图片来源与非 READY 状态）、epoch/count/digest；
  初读→逐批媒体认证→最终共同 SQL 重验保持同一集合回执，不缓存全库 ID/正文/图片 Map。
  同时最多一批轻量 metadata、单源计数和一幅 ≤16MiB 明文；逐批/逐幅检查 O(1) 请求门。
  不借用备份 64MiB 总额，不新增全库图片/保存数量上限；总耗时随合法媒体量线性增长，性能未验。
- 单源图片关联超限与“已采纳线索 1000 命中预算”使用不同错误，不能误导为收窄关键词即可修复。
- 原文内部候选必须以 READY EXISTS 在候选预算前筛选，并让私有 proof 保留 READY 与别名模式；
  最后复核不可误调旧 any-state 候选。所有嵌套复核传入同一 guard，不删原图片集合重验。
  profile2 完整验证全部历史/来源，显式计算各条件；仅当前 ACCEPTED、逐字且全部条件满足才
  消耗 1000 命中预算，1001 整组拒绝。A/I/P 继续完整认证其原有集合/媒体和保存恢复上限。
- 搜索、分页、resolve、私有返回种子都绑定布尔条件与 owner proof；旧端口不支持时明确拒绝。
  最终共同事务重验各类型/全部来源/图片回执，真实提交 ACK 后才发布或确认导航。
  失败/丢 ACK 不发部分结果、成功空集或导航；取消等待实际 SQL/文件读取释放，不取消共享 actor。

## 删除、ABA 与真实写入退休

- 当前附件变化不推进文字索引 epoch，仅终态 digest 无法发现空→READY→空 ABA。
  提议在现有 `LocalSearchSqlSchema.guards` 附加 [三条精确候选触发器](design/D06_READY_IMAGE_EPOCH.sql)：
  PNG/JPEG INSERT/实质 UPDATE/DELETE 同事务推进**现有** `local_search_epoch`，不清 FTS/documents、
  不重排作业。音频-only 和无变化 UPDATE 不推进；历史图片变化也保守失效旧查询。
  不新增表/列/Room 版本或备份格式，但这是新增 SQL trigger 行为，需本设计独审。
- 现有 create/onOpen 安装路径须覆盖旧库冷开及真实主机 fixture；epoch 既有溢出保护不降级。
  full backup 已排除 epoch；所有全备份/选择包/恢复 journal 仍在原事务中自动推进它，
  回滚同时回滚 epoch；旧格式字节与旧表/列保持。需验证恢复/迁移，不以“没有新字段”跳验证。
- 已排查全备份/选择媒体包/journal/草稿落正式记录均为普通 INSERT；未找到附件 REPLACE 调用。
  但 UPDATE-only intent guard 无法阻止 SQLite OR REPLACE；其隐式 DELETE 默认不触发删除 trigger，
  图→音可连图片 epoch 一并绕过。补 [同 ID BEFORE INSERT 拒绝规则](design/D06_ATTACHMENT_IDENTITY.sql)，
  与原 UPDATE guard 同源安装在新建/迁移/冷开和主机 fixture；不是修改恢复数据或允许重用 ID。
  普通插入冲突/REPLACE/UPSERT 同 ID 改 intent 必须原子拒绝，原 row/epoch/密文保持；正常重试
  仍走既有 read/ack，同次恢复 DONE/journal 重读，不以 REPLACE 覆盖。必要真实 SQL 反例单列保全。
- 新图片/重选在来源与当前 selection 核验、CAS 接收稳定选择请求后，于 CAS 外/入队前
  退休过滤会话及 returnSeed，含 UI-null handoff；文件读取生成完整 intent 后，actor 再核
  owner/来源版本/request，匹配后才写。包恢复/状态变化/原件改版或删除也覆盖真实变更准入。
  不能只禁用按钮；未获准的旧请求不触发退休，旧失败/迟到回调不能伤新会话。
  读图片/列附件/精确回跳不是写入，不应因此清掉正常返回种子；UNKNOWN 不复活旧 proof。
  图片、选择包/全备份恢复及回收删除的准入通知都在 sourceUi/capture monitor 外、入队前执行，
  避免与导航持 lifecycle→sourceUi 的顺序反转。恢复读取仅在实际图片状态变更前通知，正常核对不通知。
- 每个 scope 开启条件后都观察全来源、owner/可用性/epoch 变化，含非命中/屏外来源；逐行门
  不遍历 UI entries/附件集合/旧 bindings。正常生产 READY 原件只读，替换用新 ID/修订，
  不允许同 intent 偷换 hash/字节；外部文件篡改在认证/重开时失败，不承诺实时监控磁盘。

## 必要红绿边界（实施验收要求，非通过声明）

| 红：必须拒绝/退休 | 绿：必须保留 |
| --- | --- |
| 仅旧修订/仅非 READY/媒体缺失、坏 tag/hash/大小/MIME、两 READY 任一损坏 | 正常 READY × 五类交集；合法非 READY + 合法 READY；图片-only 空正文不造字 |
| 未选/非命中/屏外图片损坏、非 READY metadata 畸形；空→非空/替换/删除/ABA/独立 SQL 对端改变 | 条件外 1001 命中不挤占唯一合格结果；条件内 1000；旧 any-state 原文语义不变 |
| 最后提交失败/丢 ACK、原文嵌套复核取消、A→B→A 迟到、来源政策/回收/永久删除 | 正常搜索→分页→精确出处→返回新查询；真实 SQL 释放后同 owner 仍可用 |
| 旧 owner/模式被丢弃、写入 UNKNOWN/退休后旧 callback、恢复前旧票据 | epoch rollback/冷开/各恢复路线；FTS/documents/jobs 与音频-only 行为保持 |

必要 UI：同一主题、48dp、复选语义/可见焦点、200% 纵横屏真实滚动/Enter/原件回跳；
新筛选行用同一生产展示组件出像素，不为了 Preview 改业务门。设计 SQL 探针单列范围，
不当生产安装、JVM 产品功能或设备通过。LOCAL_ONLY，无新 SDK/条款/模型/权限/备份格式。
