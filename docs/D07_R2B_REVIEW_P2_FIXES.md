# R2b 实现独审：五组 P2 修复候选

父级对 `236d0d8f1ca2dc14bdca03c0019c4cc40775a55e` 的实现独审为暂不 ACK：五组确定 P2、无确定 P1。
本轮只修复这五组；不推送、不启动 CI、不改 main，不增加 SDK、权限或费用。
旧候选及其证据保持原样，本文件补充后续修复，不能把旧候选主机结果算作本轮实际执行。

1. 两秒 expiry 通过现有幂等 `forget` 退休并移除当前确切票据；Binder 返回后重新检查 elapsed deadline。
   即使主 Looper 尚未执行 expiry，跨期成功也会释放占位。移除 RAM reservation 不关闭 owner，不推导 Binder 已取消。
2. 同 identity 捕获后，`beginHandoff` 在登记 guard 下验证当前票据并原子认领一次交接。
   重复 receiver 只合并，不再 enqueue、goAsync 或设置第二个退休计时器；容量仍为 64。
3. Job 停止使用公开 jobId + 进程 nonce + JobInfo 中的运行代次。
   每个 submission 只认领一次运行；结束/停止封口该代次的票据，新提交使用新代次。
   最后一次空 dequeue 前封口该代次的新准入；已有绑定但尚未返回的 enqueue 在协程中等待，不在 guard 内等待 Binder。
   成功接受的 work 完成后由空 `dequeueWork()` 自动停止系统队列，不手动 `jobFinished()`；新提交使用新代次。
   不再依赖 Java 对象 `===`；迟到旧代次 stop 不影响替代运行，不使用隐藏 Binder API或无代次全局停止位。
   JobWorkItem 仍只有原 process nonce + ticket；JobInfo 的代次也只是 RAM 归属提示，不能代替票据、owner 或持久 claim。
4. 正常前台 dispose 使用独立释放通知，进入 RELEASING，关闭确证后才回 NO_FRONT。
   显式锁、未知访问、失败关闭保持禁止冷准入；新的 cold candidate 仍必须重新读取严格偏好。
   启用保护即使普通释放后也不冷开；旧 session 票据永不迁到新准入。
5. 前台开库失败的排空/关闭经实际工厂调用的 `RecordOpenFailureProtocol` 处理。
   未确认关闭保留 native/file handles 与主 key，抛出带关闭能力的隔离错误；外层 openProtected 不再先 wipe。
   prepareFront 保存 sealed 占位；重试先在同一 worker/close gate 确认原资源清理，失败不调用第二次 factory。
   完整 owner 的 database close 失败也不 wipe 主共享 key，不把第二次 awaitClosed 当成成功 ACK。

先红后绿证据包括：旧代码第 64 个占位拒绝新工作、延迟主 Looper 下跨期成功仍有效、重复交接杀死首份成功 work。
早期容量回归曾在循环内手工 forget 旧票据而掩盖问题，已移除这条测试清理并保留两轮原始日志。
其余回归覆盖不同 params 对象停止、旧代次 stop、已开始调用结果尾部、普通释放偏好分支、未返回工厂的真实 SQLite 关闭失败和确证后重试。

全部数据/角色为合成；SQLite/平台端口是受控主机证据，不是设备 SQLCipher、真实权限/闹钟/通知或 OEM 验收。
Android AGP APK/lint/Compose/Preview 与设备边界继续 NOT RUN。修复候选等待父级再次独审，保持不推送。

停止参数对象与队列完成语义依照 AOSP Android 13 公开源码核对：
[JobServiceEngine](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/apex/jobscheduler/framework/java/android/app/job/JobServiceEngine.java)、
[JobParameters](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/apex/jobscheduler/framework/java/android/app/job/JobParameters.java)。
这些是平台合同依据；测试 shadow 只控制公开参数、队列和回执，不证明设备 Binder 时序。
旧运行收尾只在没有有效待执行票据时关闭 cold owner，避免停止 A 的已开始调用收尾封死排队 B 原本仍有效的 owner。
