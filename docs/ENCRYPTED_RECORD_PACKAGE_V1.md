# 加密当前原文包 v1：F08 / §14.4 / D08 有限切片

本片以已接受的回收站提交 `caf1db0a6074bd45cecd9e00f6a95be8649d8b1a` 为基线。
原始两份主方案保持不变。现有 Markdown/JSONL 是普通明文导出，不能代替此加密包。
本片仅包含用户选择的当前原文与手工标签，并提供完整认证后恢复为新副本。
附件、历史版本、任务、草稿、AI 派生、服务密钥及设备密钥均不进入包；不是整库备份、
整库迁移或完整 D08，也不声称设备 / Keystore / 全新安装恢复已经验收。

## 用户合同

用户明确选择 1–20 条当前可见原文，预览完整原文、标签、捕获时间和时区，确认有限范围，
输入并再次确认独立恢复口令，再选择系统 SAF 创建文档目的地。口令丢失无法恢复。
首版口令为 12–128 个可打印 ASCII 字符（U+0020–U+007E），至少一个非空格字符；
大小写、开头和结尾空格逐字使用，绝不 trim 或 Unicode 归一化。此限制使 JCA 不同
PBE 字符转换约定的输入字节一致；原文和标签使用完整严格 UTF-8，包含中文和补充字符。
这不意味着长度合格的口令一定难以猜测。口令不是数据库 / Keystore 密钥或模型服务密钥。

保存前及打开输出流前均重新核对同一原文、标签版本和 visibility generation。
选择器结果与一个内存会话 / epoch / 不透明请求身份绑定；旧回调不能写新目标。
成功须包含写入、flush、close 和同一文档的完整密文字节读回。失败或取消可能留下空文件
或部分密文；保留并提示，绝不自动删除，不承诺通用文档提供商具有事务或物理擦除能力。

恢复先由用户选择 SAF 文档并输入恢复口令。输入完整受限读取、AEAD 完整认证和全部
manifest / 记录解析均成功后才显示完整预览；之后另一次明确确认才会提交数据库。
首片导入本包全部条目，不提供部分包导入。每条生成新 UUID，初始原文版本为 1、
策略为 `AI_NONE`、无 connection / consent；保留原捕获时间与时区，持久化包 ID、
原始记录 ID / 版本 / 标签版本 / 内容指纹的来源追溯。不覆盖任何当前记录，不改变或
复活任何回收站记录，不导入远端授权、连接、AI 结果或旧设备绑定密文。

同一包 ID 与同一已认证明文摘要是一个稳定恢复操作。相同包重复确认（包括冷重开和
丢 ACK 后重试）读回第一次结果，不再创建副本。同一包 ID 的不同内容拒绝。副本后来
编辑、授权或移入回收站不会被重放恢复覆盖或复活；重放是只读结果核对。
如果需要另一个独立副本批次，应另行制作新包；首片不提供绕过幂等回执的“再导入”按钮。

## v1 密码学 profile

使用平台维护的标准 JCA `SecretKeyFactory` / `Cipher`，不复制 KDF / AES 实现，
不指定非 Keystore provider，不添加新密码学依赖、网络服务或安装步骤。

| 项目 | v1 固定值 |
| --- | --- |
| KDF | `PBKDF2WithHmacSHA256` |
| PBKDF2 输入 | 上述 ASCII 口令的逐字节值，无预哈希或额外口令变换 |
| PBKDF2 迭代 / 输出 | 恰好 600,000 / 256 位 |
| salt | 每次新封装由 `SecureRandom` 提供 16 字节 |
| AEAD | `AES/GCM/NoPadding`，256 位 key，128 位 tag |
| nonce | 每次新封装由 `SecureRandom` 提供 12 字节 |
| AAD | 下述完整 48 字节头部 |
| scope | `currentOriginals`, `manualTags` |
| excluded | `attachments`, `originalHistory`, `tasks`, `drafts`, `aiDerived`, `serviceKeys`, `deviceKeys` |

600,000 是本 profile 的有限成本选择，不声称它是 NIST 针对手机规定的迭代数或已测
设备延迟。解码只接受这个精确值，不能让文件指定任意昂贵参数。不同的未来 profile
必须使用新的 suite / 格式版本，不降级或猜测算法。随机 salt 与 nonce 从不人为复用；
同一已生成密文的文件重试只复用相同密文，不用相同 nonce 加密不同内容。

使用 `PBEKeySpec.clearPassword()`，清零受控 password / key / 暂存 plaintext byte 数组，
尽可能销毁派生 `SecretKey`，结束后退役内存输入和工作 owner。Java immutable String、
Compose / provider 内部副本无法作全堆物理擦除保证。无明文临时文件或磁盘缓存；
口令、正文、标签、content URI 和确认内容不进入日志、导航参数或 SavedState。
AEAD 认证证明密文完整性和口令匹配，不提供来源作者的数字签名；追溯元数据由包提供。

## 外层二进制头与限额

所有整数为大端。只有产品封装 / framing 是本项目格式，密码学是上述标准原语。
文件不是 ZIP，无压缩、Java serialization、路径解压或设备数据库密文副本。

| 偏移 | 长度 | 值 |
| --- | --- | --- |
| 0 | 8 | ASCII `AIYOUPK1` |
| 8 | 2 | unsigned formatVersion = 1 |
| 10 | 2 | unsigned suite = 1 |
| 12 | 4 | iterations = 600000 |
| 16 | 16 | salt |
| 32 | 12 | nonce |
| 44 | 4 | 完整 ciphertext + tag 的长度 |
| 48 | 声明长度 | 密文与 16 字节 tag |

文件最多 2,097,152 字节；明文最多 2,097,088 字节。头不足、未知版本 / suite、非固定
迭代数、负数或超限长度、声明与实际长度不同及尾随额外数据均在 KDF 前失败。
受限 stream 读取最多上限 + 1 字节，连续零长度读取有有限预算；不按未验证长度分配。
完整 `Cipher.doFinal` 认证成功前，不解析或发布任何部分明文，不调用数据库导入。

## 认证后的 manifest 与记录 framing

没有可选字段、可执行对象或递归结构。UTF-8 字符串均以 4 字节非负字节数 + 严格 UTF-8
编码表示；读取先检查字段上限及剩余输入再分配。拒绝畸形 UTF-8、未配对 surrogate、
未知 schemaVersion、不完整字段、重复源 ID、非 canonical 标签及任何多余尾部。

下表规定唯一顺序。I32 是 4 字节有符号整数，I64 是 8 字节有符号整数，均大端。
S 是 I32 非负 UTF-8 字节数 + bytes；A 是 I32 非负元素数 + 各元素。字符串数组按下表
固定顺序，不按集合重新排序；所有字段 / 数组上限在分配或循环前检查。

| manifest 字段（顺序） | 编码 / 必须值 |
| --- | --- |
| formatName | S，`AIyou-record-package`，最多 64 bytes |
| schemaVersion / formatVersion | I32 / I32，均恰好 1 |
| backupId | S，恰好 36 bytes，canonical lowercase UUID |
| createdAtMillis | I64，非负 |
| kdf / aead | S / S，恰好 `PBKDF2-HMAC-SHA256` / `AES-256-GCM`，各最多 32 bytes |
| iterations / keyBits / saltBytes / nonceBytes / tagBits | 五个 I32，600000 / 256 / 16 / 12 / 128 |
| scope | A<S>，恰好按顺序 `currentOriginals`, `manualTags` |
| excluded | A<S>，恰好按上方 profile 的七项顺序 |
| fileCount | I32，1–20 |
| 全部 file 描述符 | fileCount 个：path(S，最多 64 bytes)，byteLength(I32，1–420000)，SHA-256(raw 32 bytes) |
| 全部 record 块 | fileCount 个：blockLength(I32) + raw bytes；长度必须等于对应 descriptor.byteLength |

SHA-256 是对应原始 record 字节块的摘要；包摘要是全部认证明文的 SHA-256，与随机
外层密文的摘要分别记录。fileCount / 250k 文本等内层上限在认证后、预览或 SQL 前检查，
外层只能在 KDF 前检查文件 / 参数 / 外层长度，不能声称未解密已验证内层限额。

路径只能依次是 `records/00.bin` 至 `records/19.bin` 中实际存在的连续前缀。不接受
任意路径、绝对路径、`..`、反斜杠、符号链接或压缩条目，也不把这些路径写到文件系统。
单 record 最多 420,000 字节。record 唯一顺序为 sourceId(S)、sourceRevision(I32，正数)、
capturedAtMillis(I64，有符号，允许 1970 年前时间)、captureTimeZone(S)、原文 contentHash
(raw 32 bytes)、originalText(S)、sourceManualTagVersion(I64，非负)、标签数组(A<S>)。

每个 source ID 最多 128 UTF-16 units / 512 UTF-8 bytes，遵循既有 identifier 校验；
sourceRevision > 0；时区 UTF-8 最多 128 字节且通过 `ZoneId.of`；原文不是空白、最多
100,000 UTF-16 units / 400,000 UTF-8 bytes。全包原文合计最多 250,000 UTF-16 units。
标签遵循既有最多 12 个、每个最多 40 code points、保留大小写 / 组合标记且无重复的
合同，单字符串 UTF-8 最多 640 字节；tagVersion 非负，0 只能配空标签。
所有 count / 长度都受这些独立上限和剩余字节约束，不能只相信总文件上限。

## 单事务副本、来源与稳定回执

借用现有 counted SQL gateway 和标签 AEAD，沿用 Room v6。只有生产密码学认证器产生的
内部 verified capability 可进入生产导入；不向 app 提供原始明文的直接导入旁路。
事务先核对已存在回执，或验证全部新 UUID 不重复、不等于包内源 ID、没有数据库冲突；
然后插入所有 Entry、version-1 EntryRevision、标签密文、无授权的 source access，以及
已有类型的 `LOCAL_INDEX` PENDING（不声称索引已执行）。任何插入失败整体回滚。
capture draft、edit draft、附件、旧记录、手工待办、旧授权与 AI rows 均不改。

组回执使用 `local_jobs` 的 `PACKAGE_RESTORE:<backupId>` 独立命名空间，operation
`PACKAGE_RESTORE`，owner 是第一条新副本的 version 1，state 为有界版本化
`CONFIRMED:<base64 receipt>`。每副本另有 `PACKAGE_ORIGIN:<newId>` / `PACKAGE_ORIGIN`
已完成来源记录，绑定包 ID / 包明文摘要 / 源 ID / 源版本 / 标签版本 / record 摘要。
receipt raw binary 最多 8192 bytes，`CONFIRMED:` 后为带标准 padding 的 RFC 4648 Base64，
整个 state 最多 10934 个 ASCII 字符，先检查再 decode，必须 canonical 且无额外 bytes。
两种 receipt 均先写 I32 receiptVersion = 1 与 I32 kind（组=1，来源=2）。组顺序为
backupId(S36)、packageDigest(raw32)、packageCreatedAt(I64非负)、importedAt(I64非负)、
count(I32，1–20)，随后 count 个 newId(S36 canonical UUID) + recordDigest(raw32)。
来源顺序为 backupId(S36)、packageDigest(raw32)、packageCreatedAt(I64非负)、
importedAt(I64非负)、newId(S36)、sourceId(S≤512)、sourceRevision(I32>0)、
sourceTagVersion(I64≥0)、recordDigest(raw32)、capturedAtMillis(I64有符号)、captureTimeZone(S≤128)。
组与来源 owner、namespace / operation / sourceRevision、包摘要、每个 record 摘要与
全部不可变 version-1 正文 / hash / 捕获元数据 / revision.createdAt 一起核对；缺失、
损坏或替换的来源记录拒绝。不要使用过滤 trash 的 entries()/revisions() DAO 做重放，
也不要求当前正文、标签或已由用户再次明确设置的政策与初次导入相等。
这些行永不成为 runnable work，既有回收站只操作 `LOCAL_INDEX` 的取消 / 恢复规则。
重放严格核对全部回执 tuple、来源行和不可变的 imported version 1，不再次 INSERT。
未来永久删除必须单独设计这些回执的保留语义；本片没有永久删除或自动清空。

事务后成功显示须完整读回结果与本机索引；丢 ACK / 读回失败不能声称成功或未发生。
保留同一个包身份用于只读回执重试，不因未知结果生成新操作。关闭排队任务可使它不写；
已经开始的事务可能完成，返回不会撤销已提交的原子导入；下一次同包认证确认核对回执。

## SAF / SavedState 与验收边界

新包流程使用内存拥有的 SAF broker，legacy 16-bit request code 与 AndroidX registry
分配的高 request codes 分离，不把 document Intent / URI 放入 ActivityResultRegistry
pending SavedState。专用 request code 是 1–65535，next=65536 为已耗尽 sentinel；不能
把高 request code mask 到此范围。分配前消费 next，SavedState 保存更新后的递增整数，
取消、异常、成功均不回收；耗尽、损坏或无法确认恢复 counter 时 fail closed，不 wrap
或 reset。SavedState 不保存 URI、口令、payload、manifest 或请求 callback。
launch 只在 live / RESUMED 且当前状态尚未保存时允许；合法结果可在 paused 时交付，
结果只按 live Activity owner、已登记 code 与 session/epoch 判定，不能要求 RESUMED。
销毁后退休旧 broker owner，重建 Activity 不采用旧 code→callback；即使 VM 保留，
旧 picker work 也必须停止，不能绑给新 Activity。专用范围未知结果直接丢弃，
不交给 Registry 保存。旧 / 关闭 / 已销毁 / 不匹配结果不打开流。
生成与恢复结果均只接受用户选中的 `content:` URI，不申请持久授权或广泛存储权限。

计划验证实际生产 PBKDF2 / AES-GCM、独立 Node/OpenSSL 标准实现互操作、严格错误输入
写前失败、真实 SQLite 的 Unicode 往返 / 回滚 / 并发 / 冷重开 / 稳定重放，以及生产
controller / Compose / raw SavedState / picker 生命周期 / 受控残留。全部只用合成数据。
Host SQLite 不等于 Android Room / SQLCipher；JVM / Node 密码学不证明设备 Keystore；
host SAF provider 不证明系统 chooser。原生 SAF、Keystore、生产重启、全新安装恢复及
完整 D08 仍 **NOT RUN / OPEN**，不等这些条件才开发此独立功能。
互操作 fixture 由现有 Node 24.19.0 / OpenSSL 3.5.7 独立生成并冻结 bytes / 来源；CI
验证冻结 fixture 与生产 JCA 双向一致，不增加动态 Node 安装或未固定的新构建依赖。

## 官方原语与许可核对（没有复制第三方实现）

- [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography)：
  推荐 AES-256 / GCM、SHA-2，不固定常规 provider；security-crypto 已弃用且不再发布。
- [Android SecretKeyFactory](https://developer.android.com/reference/javax/crypto/SecretKeyFactory)：
  `PBKDF2withHmacSHA256` 支持 API 26+，匹配既有 minSdk 26。
- [JCA standard names](https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html)、
  [PBEKeySpec](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/spec/PBEKeySpec.html)：
  标准原语与可清理 char[]；ASCII profile 避免不同 PBE 字符转换约定。
- [OpenJDK maintained PBKDF2 implementation](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/com/sun/crypto/provider/PBKDF2KeyImpl.java)
  与 [OpenJDK license](https://github.com/openjdk/jdk17u/blob/master/LICENSE)：GPL v2 with Classpath Exception；
  使用现有已固定 JDK 17.0.17+10 的实现作 host 测试，不复制或随 APK 打包 JDK 代码。
- [Android Conscrypt project](https://github.com/google/conscrypt) 与
  [license / notices](https://github.com/google/conscrypt/blob/master/LICENSE)：Apache-2.0 及所列附属 notices；
  它是 Android 相关 JCA provider 实现来源之一，不声称所有设备的 PBKDF2 都由 Conscrypt
  提供，也不新增或固定 Conscrypt 依赖。设备实际 provider 兼容性仍需原生验收。
- [NIST SP 800-132](https://csrc.nist.gov/pubs/sp/800/132/final) 与
  [SP 800-38D](https://csrc.nist.gov/pubs/sp/800/38/d/final)：标准 PBKDF / GCM 基础。
- [Node license / notices](https://github.com/nodejs/node/blob/main/LICENSE) 与
  [OpenSSL license](https://github.com/openssl/openssl/blob/master/LICENSE.txt)：Node MIT 及附属 notices、
  OpenSSL Apache-2.0；只使用现有工具生成合成互操作 fixture，不复制实现或打包进 APK。
- [Activity result lifecycle](https://developer.android.com/reference/android/app/Activity#onActivityResult(int,int,android.content.Intent))：
  合法结果在 paused 时交付，broker 结果 owner 检查不能误要求 resumed。

先独立审查本合同及实现，再按最终完整 SHA 的实际回归 / CI / APK / 远端差异交付。
