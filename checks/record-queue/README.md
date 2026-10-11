# Host queue regression boundary

This standalone JVM test project compiles the actual app ViewModel and pure
model/domain sources via a Sync task, with real coroutines and JUnit. Small
Application/lifecycle/repository stand-ins isolate controlled queue failures.
It is never included in the app's Android project or APK dependencies.

Run `scripts/gradle.sh --project-dir checks/record-queue test` from repository root.
Three event-ordering regressions cover a prior failed draft write, genuine save
failure and failed readback after commit, including later input and identity
preserving retries. The XML must contain three passing non-skipped testcases.
This is host queue behavior evidence; Android lifecycle, encryption, Keystore,
Room, durability and device performance require the separate actual native tests.
# 原文编辑主机边界

同一主机工程还编译真实 `OriginalEditUiState`，以领域接口兼容的合成仓库验证原文编辑队列。新七项测试保留原版本、独立捕获草稿、比较预期版本、提交后只重试读回、失败/取消/旧会话和完整输入边界；不替代 SQLCipher/Room/设备验证。生产默认构造仍只打开现有加密仓库，测试注入不引入明文生产回退。

同一工程编译真实 `EncryptedAttachmentStore`，新增17项以真实AES-GCM文件与可重开测试账本验证附件中断恢复/原子发布/认证/争用和资源边界。测试账本不是Room；主机文件fsync不是Android设备证明。详见 `docs/D02_ATTACHMENT_STORAGE.md`。

历史切片同时编译真实 `OriginalHistoryUiState`；新七项 queue 测试验证只读历史、编辑与此刻输入保留、列表/选版读取失败、关闭重开迟到请求、读期间版本变化/删除、缺失选版和错误版本集。全套现为 21 项真实主机测试，仍只覆盖合成 IO 边界。

当前完整套件为50项：28项文字队列/编辑/历史边界、17项附件同进程协议、5项真正独立JVM PID回归。子JVM通过显式test runtime classpath运行Sync编译的真实`EncryptedAttachmentStore`。15种场景启动31个独立子PID，仅对14个明确自有且已到阶段的写入PID执行有界强制终止，全部确认退出；新PID完整认证读回及错误身份/源/hash/key/tag拒绝均须通过。测试账本是单记录合成磁盘实现，不能当Room/SQLCipher或Android跨PID证据。

失败和成功的每次独立进程场景都保留在`build/reports/attachment-process/`，含JSONL事件、子stdout、实际PID/阶段/result标记和合成账本/密文。只包含固定合成fixture，不包含真实key/用户附件；子进程不输出源字节或密钥。JUnit XML守卫除5项具名方法外，还要求31个独立PID、14阶段终止、31退出确认和认证/不重写证据。CI把此目录随现有reports artifact保留，不更改39步或依赖版本。详见`docs/D02_ATTACHMENT_PROCESS.md`；所有原生17项和物理断电/16KB等仍未完成验收。
