# 用户主动加密当前原文包与新副本恢复

此切片对应两份原始主方案的 F08、§14.4 及 D08 中的一部分。它仅包含用户明确选择
的当前原文和手工标签；现有 Markdown/JSONL 仍是明文开放导出，不是此加密包或
整库备份。附件、历史版本、待办、草稿、AI 派生、服务密钥和设备密钥均不包含。
完整 D08、永久删除、原生 SAF/Keystore、生产重启和全新安装恢复仍为 OPEN / NOT RUN。
两份原始主方案保持原样。精确 v1 framing / 参数 / 限额见
[ENCRYPTED_RECORD_PACKAGE_V1.md](ENCRYPTED_RECORD_PACKAGE_V1.md)。

## 制作与恢复

从“回忆”打开“加密原文包与恢复”。制作时默认不选任何记录，最多选择 20 条，
在同一仓库事务取得当前原文、标签和捕获时间/时区，显示完整预览。另行确认有限范围，
两次输入独立恢复口令（12–128 个可打印 ASCII 字符，首尾空格和大小写原样使用），
再通过系统 SAF 自行选择目的地。口令丢失无法解密。预览后源版本/标签/visibility
generation 变化会在加密或文件打开前拒绝，不自动把新内容塞进旧确认。

使用平台标准 PBKDF2-HMAC-SHA256（固定 600000 次）、AES-256-GCM、每包随机
16-byte salt / 12-byte nonce，完整 48-byte header 为 AAD。所有外层大小、版本、
suite 和 KDF 参数在 KDF 前验证；内层字段、UTF-8、文件描述符、正文 hash、计数、
路径和总文本限额在完整认证后、预览或写库前验证。不压缩、不解压、不复制设备数据库。
受控明文写入固定有界内存缓冲区，避免扩容遗留旧数组；可控口令/派生 key/明文字节
均退役清理。JCA/Compose/immutable String 内部副本不能作全堆物理擦除保证。

恢复先选择文档并输入口令。完整认证和严格解析后才提供完整原文/标签/时间/时区
预览，之后单独点击“确认恢复全部为新副本”才进入数据库事务。新副本使用新 UUID、
原文版本 1、AI_NONE、无连接/同意授权；标签使用本机标签 AEAD 重新加密为初始版本。
保留原捕获时间/时区和文件提供的来源 ID/版本/标签版本/包创建时间/摘要。AEAD 认证
不是作者数字签名。详情显示恢复副本来源，用户以后明确修改、移入回收站或授权时
遵循本机现有行为。

## 原子性、回执和退出

借用同一 drained SQL owner 和标签密钥，沿用 Room v6，无 schema 或依赖升级。
一次事务写全部新原文、版本、标签、AI_NONE access、PENDING 本机索引作业和完成态
组/来源回执。任意失败整体回滚；捕获/编辑草稿、附件、手工待办、旧原文、旧授权和
AI rows 均不改。完成态 PACKAGE_RESTORE / PACKAGE_ORIGIN 永不成为 runnable work。

同一 backup ID + 已认证明文摘要是一个稳定恢复操作。丢提交 ACK、读回失败或冷重开
后，同包重新认证确认只核对第一次回执；不自动另建一批副本。同 ID 不同摘要拒绝。
重放使用 raw SQL 核不可变 version 1 和来源回执，允许当前正文、标签、授权或
回收站状态改变，绝不覆盖、清除新授权或复活旧副本。回执缺失、损坏、元数据替换
或无法核对时失败，不把未知结果报告为未发生。未来永久删除须单独设计回执保留语义。

排队工作可在取消后不写；已开始的事务可能完成。退出后仍完整刷新真实读回的本机
记录，但不会显示旧面板成功确认。读回失败保留同一已认证包和操作用于核对。
文件必须完成写入、flush、close 及同一文档全部密文字节读回才确认。取消/失败可留
空文件或部分密文，应用不自动删除、不重新覆盖旧目标，也不承诺 provider 原子性、
云盘后续同步或物理擦除。

## 生命周期与验证边界

新流程的 legacy SAF broker 使用 1–65535 递增请求码，分配前消费，所有结果均不回收；
65536 为耗尽 sentinel。SavedState 仅保存 counter，错误/缺失的恢复 counter 拒绝。
launch 需 live/resumed 且状态尚未保存；合法结果可以 paused 交付。重建 Activity 不接管
旧 callback/owner，旧结果和未知专用码直接丢弃，不进入 AndroidX pending-result
SavedState。只接受用户选择的 content URI，不申请持久授权或广泛存储权限。
口令、正文、标签、manifest、确认状态和文档 URI 不进日志、导航参数或 SavedState。

回归使用真实生产 JCA、独立现有 Node 24.19.0 / OpenSSL 3.5.7 冻结互操作 fixture，
标准 KDF 入口计数证明无效外层输入执行 KDF 为零，完整 Unicode/恶意认证格式验证；
host SQL 使用真实导出的 v6、生产 SQL/标签 AEAD 和独立 SQLite PID，覆盖回滚、并发、
冷重开、丢 ACK、真实新授权、回收站、来源损坏和 owner drain；控制器覆盖真实
StateFlow CAS 边界和受控文件失败/取消。Compose 使用真实 UI/controller/JCA、合成
仓库端口；broker 测试使用实际 ComponentActivity，provider 测试使用实际 ContentResolver
与合成 ContentProvider。它们均不冒充 Android Room/SQLCipher、设备 chooser、Keystore、
生产重启或全新安装恢复。

最终交付必须绑定完整 Git SHA、实际 XML、严格回归/CI 与 APK 清单。编译或 DEX
收集不算设备执行，完整 D08 保持 OPEN。
