# 单条回收站永久删除：工程合同

基线为已有限接收的 `e252615809e55b090edcb545379602fae53bed25`。
对应原始主方案与产品架构的 F02/F07/F08、§10.6、D08；两份原方案保持原样。
本片提供真实的单条删除路径：用户从回收站选定记录，查看范围并明确确认。
删除完成指当前应用数据库中可达的原文、全部历史、标签、派生内容及登记的私有
附件/编辑草稿均已清理；保留必要幂等墓碑。它不宣称介质取证擦除，不撤回用户
已导出的外部文件，不删除独立手工待办、其他记录、共享密钥或未知文件。
批量清空、再次导入同一备份的新副本模式与完整 D08 设备验收不在本片。

## 用户确认与稳定身份

只接受已在回收站的当前记录。确认绑定精确 source ID/revision、visibility
generation 和一次性客户端 mutation ID。取消确认不写库、不删文件。
确认后开始持久删除，不能恢复该源；失败显示“删除未完成”，显式重试同一个
意图，不换 ID、不把提交回执不确定当作没有发生。旧页面、旧目标、同页重开、
导航、读取失败和 dispose 均不能提交或采用新目标的结果。条件与源正文只驻内存，
不进 SavedState、日志或普通文件；诊断字符串只含阶段等非正文状态。

## 同一加密数据库与删除阶段

继续使用 records.db、SQLCipher、原有密钥和同一 counted/draining SQL owner。
Room v8 使用独立删除回执、包回执及自有文件创建证明表；回执没有指向被删源的外键。
7→8 只增表并逐字节复制既有 PACKAGE_RESTORE/PACKAGE_ORIGIN 回执，保留原表
及 v2–v7 schema。实际 v8 export/asset 必须由既有许可 CI KSP 生成，再提交真实
字节并通过下一轮 CHECKED_IN 同 SHA 门禁；不得手写导出或 destructive fallback。

1. PREPARED：短 SQL 写事务重核已删除源版本/代次，持久化稳定意图及永久 source
   fence；此时不宣称文件已排空或删除。相同意图可读回；冲突、非法状态与缺失
   证明失败关闭。任何 fence（包括 DONE）均禁止旧源 ID 再插入及回收站恢复。
2. PLANNED：释放 SQL 后，用已有文件协议按附件→编辑草稿顺序获取两个
   `.store.lock` 的真实 exclusive tryLock。第二锁 Busy 释放第一锁，保留同意图。
   不持 SQL 事务等待锁，不用 VM 队列或单进程布尔值代替实际排空。成功取得两锁
   才证明遵守该协议的既有文件操作已退出；锁内重核并冻结稳定自有文件清单。
   完整名单在真实 SQL 事务提交后，必须再次读回同 intent/source/plan bytes；仅内存
   名单或提交 ACK 不确定不能授权 unlink。双 lease 计入实际 store 操作数，并持到
   最终 SQL 提交退出；close 已 seal 则不能从 PREPARED 的间隙新进入。
3. 删除清单中已确认的文件并 fsync 相关目录。每项使用固定私有目录、规范文件名
   与冻结观察证明；不跟随符号链接，不扫描清空目录。未在并不自动等于持久删除
   已确认；重复调用仍核目录 durability。观察发生变化时保留并报未完成。
4. DONE：持两锁的最终 SQL 写事务删除源、全部 revision、附件行、标签、AI job/
   annotation、相关源作业及派生 FTS/doc，正确递增索引 epoch，并写最小完成墓碑。
   文件删除与 SQL 提交不能伪称同一原子事务；中断保留阶段/清单，下次同意图继续。
   所有自有数据确认清理后才显示完成。其他记录/独立待办及其回执逐字节保留。

## 文件写者、重开与关闭

所有 save、FAILED 重试、STAGED 恢复发布、编辑草稿写入均在持各自 store 锁后
查询持久 fence；发布阶段也拒绝已封闭源。PREPARED 与 DONE 都阻止旧写重建文件。
启动先恢复/识别 pending erase，再做普通附件/编辑恢复；被封闭源不发布旧附件、
不重新列为可恢复草稿。每次只做有界、明确的工作；失败停止自动循环并提供重试。
保证限定于使用更新后 fence 与共同锁协议的实现，不宣称未知旧程序也遵守协议。

附件 ledger 只给出候选路径，不能证明当前占用文件自有。初次规划还须用完整
canonical AAD/intent 认证，或核对成功 CREATE_NEW 后已持久登记的稳定创建观察；
只限该源各 revision 的精确 `.tmp/.aead`。预占同名未知文件、创建结果不确定且
不能认证的截断文件保留并报未完成，不以 WRITING/FAILED 状态或现有 attrs 推断归属。
编辑主文件是精确 `hash(entryId).edit`。合法旧 `.edit.partial.UUID` 必须用主文件
canonical AAD 及认证后的 entryId 确认归属；当前直接 authenticated(partial) 的 AAD
不适用。截断/无法认证且没有登记的旧 partial 保留并报未完成，不能凭前缀当所有权。
未来附件/编辑 partial 创建前登记精确预留名称，成功 CREATE_NEW 后再登记稳定
创建观察；不能把“预留”当“已创建”。创建证明使用同一加密数据库的独立 ledger，
不得因源 FK 提前丢失。确认发布/目录同步后只清理不再需要的临时证明，主文件仍须
可认证。创建结果不确定且没有证明的窗口失败关闭，不假称所有文件均已删除。

成功创建后的证明还要求随机 AEAD header 已 fd/目录同步，再登记 fileKey+header
摘要；不依赖后续会改变的 size/mtime 或可能回退的 creationTime。PLANNED 才冻结
完整 attrs 和密文摘要。目录身份绑定目录与已持锁文件的两个 fileKey，不使用目录
时间戳；仅适用于保留目录和共同锁文件的合作实现。provider 无 fileKey、身份变化
或未知外部替换均报未完成，不把 host Linux 支持推成 Android 原生通过。
初次规划最多 64 附件行、64 编辑候选、192 文件、64 MiB 候选密文；编辑目录最多
枚举 4096 项。超预算不截断为 DONE，不自动无限重试。包组核对最多 4096 组，
超过则失败关闭。这些是本片工程预算，不宣称已完成任意规模的 D08 验收。

owner 关闭先封闭两个文件 store 和新增借用 SQL，再在 IO 上等待实际文件操作及
借用 SQL 排空，最后关闭 Room/擦共享钥。保留非阻塞 close，另提供 owner 的
awaitIdle；不在操作内部自等待，不通过提前关闭 Room 伪称排空。不增加权限、
凭据、SDK、许可证、模型调用或目录访问；实际设备生命周期仍需原生验收。

## 同包幂等与已删成员

包回执的独立 ledger 保存迁移后的原始 CONFIRMED 字节，作为 group/origin 权威。
旧 local_jobs 行在真正清理目标之前保留。删除首项、中间项或全部项均不移除 group。
v8 新恢复也必须在同一个实际导入 SQL 事务写独立权威 group/origin；重放只读该权威
ledger，旧 local_jobs 仅作兼容副本。缺组时，存活镜像或最小删除证明只用于拒绝
不确定的新批次，不能据此自动修复或宣称旧恢复成功。未写权威回执就
不能确认导入，所有成员被删后仍拒绝把相同 backup ID 作为新导入。
删成员的完整 origin 可清理，仅留绑定 group/digest/ordinal/newId/recordDigest 与
删除完成墓碑的最小证明；DONE 同事务实际清理两套 ledger 的完整 origin，只留
group 原始字节和最小成员证明。未删成员仍核原始导入 revision1/hash/capture 元数据。
缺失且没有合法删除证明仍失败；不能因为一项被删使其他 origin 读取失效。

当前产品一个 backup ID 的再次操作仍是核对旧恢复回执：已删成员不 insert、不恢复，
返回并显示已删/正在删除数量，不能继续说“新恢复了全部记录”。新 session、新 IDs
或再次点击原有确认按钮都不转换为新副本。再次新副本如以后开放，必须有独立
NEW_COPY 确认和稳定 restoreAttemptId；UNCERTAIN/迟到回调/冷重开不自动切换。

## 必须取得的证据与剩余门

实际生产 SQL/AEAD、同一真实 SQLite 文件、独立连接/PID 和两个真实 store 锁：
旧文件写者暂停时删除只 PENDING；退出后重开继续；DONE 后旧写 source opens=0
且文件不重建。覆盖 unlink/fsync/最终 SQL 中断、未知/替换/符号链接保留、合法旧
partial 与截断 partial、全历史/标签/附件/FTS 清理、其他记录/待办不变。
实际包首项/中间项/全部项删除后的旧 replay、剩余 origins、伪造或缺失墓碑拒绝。
真实 VM/Compose 确认取消、同意图重试、读取失败、旧目标/导航、SavedState及大字窄屏。

唯一集成人正常开发分支提交/推送，冻结源码、实际 XML、失败日志、APK 摘要及同
SHA 全量 CI，再独立只读终审。Room/SQLCipher、Keystore、生产重启、SAF、设备
UI/性能未运行则保持 NOT RUN；完整 D06/D08 不因 host/编译证据关闭。
