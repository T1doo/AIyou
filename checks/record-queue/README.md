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
