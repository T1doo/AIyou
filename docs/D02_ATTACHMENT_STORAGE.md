# D02：附件加密存储与中断恢复

原方案 Master Plan §8.1/9.1/14.2/17 与 Architecture 对应段把附件状态机列为 D02。此前只有文字、草稿、版本和作业账本，没有附件模型/接口/关联表/文件恢复。本切片补齐这个存储边界；相册选择、图片解码、录音、播放和混合输入 UI 属于 D03，仍未开放。D08 删除与备份未提前实现。

## 已实现的链路

`AttachmentStore` 接收稳定 UUID、原文 ID/版本、媒体类型、源字节大小及 SHA256。它把附件关联到**固定不可变原文版本**，不会跟随原文编辑重绑。Room v3 新增附件账本，v2→v3 保留原有四表；旧 v1→v2 迁移保留。附件意图的源版本/类型/哈希/大小有 SQL 防改写触发器。文件名由校验后的 UUID 生成，不接收用户路径。

附件目录仅在已存在的本机记录目录下创建，进入协议前同步其父目录，让新增子目录的entry有明确持久化边界；不递归创建未知祖先。父目录同步失败即停止。先持久化 WRITING 意图，再把源流直接写成 AES-256-GCM 密文临时文件；没有明文临时文件。完整源大小/哈希匹配且文件 fsync 后提交 STAGED，再完整验证 AEAD tag、原始哈希和大小，执行同目录原子重命名与目录 fsync，最后提交 READY。原子移动不支持时失败，不回退到 copy/delete。返回 READY 前不会显示附件保存成功。真实断电/Android文件系统持久性尚待设备验收，主机fsync调用成功不等于完成该验收。

GCM 使用平台 Cipher，不指定非 Keystore provider；每次加密用 SecureRandom 产生新的96bit IV和128bit tag。版本化封装为5字节 `AIYA1`、12字节IV、密文与16字节tag。AAD绑定版本、附件ID、原文ID/版本、媒体类型、哈希、字节数。生产媒体密钥是独立随机256bit key，由既有设备 Keystore wrapping key保护，但使用独立AAD；数据库密钥封装字节与用途不变。缺少已有附件的 key/envelope时失败，不替换密钥或原始文件。标准算法选择依据[Android Cryptography](https://developer.android.com/privacy-and-security/cryptography)，完整认证行为依据[Cipher](https://developer.android.com/reference/javax/crypto/Cipher)。封装与平台端实现仍需独立安全/设备验收，不宣称密码学审计已完成。

读取先在有界内存中验证整份密文后才返回原始字节，避免流式泄露未认证明文。内部上限16MiB是资源安全边界，不是经过设备验证的最终媒体产品限额。没有缩略图、远端上传副本或原图元数据转换。

## 恢复与失败

现有本机启动在读取草稿/记录前调用 `EntryRepository.prepare()`；真实仓库执行附件恢复。WRITING中断变为FAILED，必须显式提供同一源再重试；只有同一持久意图的临时文件可被该次重试删除。STAGED的完整密文无论停在临时名还是已经发布，都能验证后恢复READY，提交确认丢失不会重复复制。READY重复保存校验已有文件后直接返回；变更意图被拒绝。篡改、截断或缺失已暂存/保存的字节标为CORRUPT并保留证据，不覆盖旧文件。未知文件/锁/进程不被删除或终止。

自有非阻塞文件锁覆盖整条协议，协调多个仓库实例与进程；锁忙立即报错。关闭立即拒绝新操作，但密钥须等已开始的操作全部释放后才清零，避免活动写入使用被擦除的key。启动恢复也完整验证READY字节，缺失或篡改不会继续被列表当作READY。它不是进程重启测试。文件系统和数据库是两个持久化边界，状态机明确描述中间状态，不宣称跨二者原子事务。新附件源尚未成功时调用者必须保留来源与重试入口；当前捕获仍为文字专用，没有虚假显示混合记录已全部保存。D03实现混合提交时必须等所有必需附件READY后再确认整条记录成功。

## 实际验证范围

主机 `checks/record-queue` 编译真实生产加密/文件代码，新增17项以真实密文文件、自有文件锁、可重开的测试用持久账本验证：成功重开、各阶段中断、最终提交失败/确认丢失、源读失败/大小哈希错误、AEAD篡改/缺失/截断、AAD绑定/错误key、稳定ID、FK测试合同、路径/符号链接、未知文件保留、争用、nonce/关闭与16MiB完整边界，以及active关闭后原key恢复和startup识别坏READY。测试账本不是生产Room；不宣称该测试证明SQLCipher或Android fsync。

四项 Python测试从真实Kotlin源提取迁移/DAO/trigger SQL，在真实主机 SQLite上执行；v2输入表来自既有实际 KSP schema2。覆盖旧四表不变、FK/重复ID拒绝、意图不变与state CAS、最终事务回滚。它们不是 Room/加密迁移验收。

四项 `AttachmentRecordsIntegrationTest` 需Android设备实际执行：真实加密附件固定版本重开/草稿作业不变、实际Room READY提交失败后启动恢复/篡改拒绝、合成加密v2→v3保全、Android包装媒体key用途隔离/丢失不替换。原13项加密记录测试及跨PID测试继续保留；v1迁移目标断言更新为真实v3，并增加空附件表断言，不删旧数据断言。CI只编译instrumentation APK，当前所有新增native执行为 NOT RUN。

SOURCE冻结独审与实际CI结果由本轮交付报告给出；本文不提前宣称通过。schema3必须取实际KSP输出，禁止手写。默认LOCAL_ONLY、无INTERNET、无真实模型/新SDK协议/凭据、工具链锁与旧UI测试/渲染契约保持。

## 剩余D02设备门槛

现有原13项完整Room/SQLCipher/Keystore、旧4.19.0库对4.19.1读取、v1/v2加密迁移失败保全、跨PID写→force-stop→读、新4项附件Android/fsync/包装key与恢复、物理16KB SQLCipher运行均未取得完整通过证据。先前TCG共享240s超时、第四例等待原因UNKNOWN保留，不重跑已知不可用路径。最小环境是已安装并接受现有SDK的主机、已解锁且ADB已授权的受支持Android设备（另需实际16KB设备），使用已构建App/test APK和现有测试入口；不新增SDK同意或凭据。
