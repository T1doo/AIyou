# D04 持久撤销与活动 HTTP

本切片接续 `db26328cf5158ef37c3aa8786ed3cf71cd10e91a`，补齐已持久化撤销到活动请求的取消链路。完整 D04、D02 设备验收与 App 生命周期接线仍 **OPEN**；正式 Android instrumentation 的27业务方法及两个probe目前仅有编译/DEX证据，设备执行仍 **NOT RUN**。

后续 transport 多请求关闭顺序修复与新增合同见 [D04_TRANSPORT_CLOSE.md](D04_TRANSPORT_CLOSE.md)。本页的246方法及输入指纹保留为本切片历史证据。

## 产品边界

`SourceBoundTextRepository.subscribe` 是必需接口。正式 `RoomSourceBoundTextRepository` 通过原有 `RemoteTextTransactions` 查询同一加密 records 数据库；host 合同只替换 SQL 平台适配器，不复制授权或撤销逻辑。内存只保存该订阅已经失效/关闭的状态，不能提供新授权。

订阅在 execute 时建立，先同步读取持久状态，再以100毫秒间隔观察当前原文、修订、策略、删除、配置、grant、generation及可信时钟。该间隔不是检测延迟上限；数据库锁、调度和平台 IO 都可能延迟观察。每次预览也重新读取持久状态，最终保存仍使用原有新鲜授权检查与候选/job原子事务。

观察到首次失效后，内部通知只记录原因并取消该 exchange 自己的 HTTP；不在通知中写数据库或执行任意消费回调。通知在 SQL 事务结束后运行，撤销事务回滚不会被当作已提交撤销。订阅失效只通知一次，普通存储异常阻断发送。可信时钟抛出的 `CancellationException` 保持执行取消语义：后台观察只用 `EXECUTION_CANCELLED` 控制信号转交 owned cancellation，不将它当作授权拒绝；同步与观察线程不会因先后顺序产生不同的取消终态。

预览准入先完成持久检查，再通过短本地锁检查取消/失效状态。观察到失效后不开始新的预览；已经获准开始的回调可以结束。持久事务结束到回调准入之间不是跨进程分布式事务，因此不能声称数据库撤销提交瞬间全局零预览，也不能撤回已发送字节。消费回调不在数据库事务或 owner 锁内运行。

订阅 close 不等待进行中的 SQL/通知，不中断原生数据库操作；close 后完成的查询不能预约新通知，close 前已预约的通知可能完成。所有终态和取消路径关闭 owned 订阅。wire completion 是协议事实，最终候选仍取决于数据库 commit 顺序；迟到取消不能改写已经持久提交的结果。重复相同候选沿用原有幂等边界，不新增 HTTP 或覆写。

每个活动 exchange 拥有一个 daemon observer。无限并发限流、原生 IO/锁竞争与检测延迟实测属于后续 App/设备验证，本切片不宣称原生性能通过。

## 验证与待办

当前检查结果和正常 push 的完整 SHA/同 SHA CI，以交付证据为准。新增合同使用两个独立 SQLite 连接进程及真实 `127.0.0.1` HTTP，只用合成原文、假 secret references 与两个明确协议，不调用真实模型。

最终实际命令 `python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --offline --rerun-tasks` **PASS**，4分53秒，16 tasks实际执行。`verify-ai-protocol-results.py` 核准13份新鲜XML、**246个唯一方法：AI178、domain13、remote-text55（原SQL28 + 原source-bound HTTP14 + 新撤销合同13）**，零failure/error/skip；方法矩阵不额外计数。输入指纹 `fb64f3ec2b8d98114037d69ca21a344a36b7836849b121f029c1b9c27006ea86`，完整日志SHA-256 `e298f23381210c0d7469fb6439032c24dc0604e51d152a872b1f3abdec0541ff`。已解析的HTTP依赖jar校验也 **PASS**。该结论只覆盖host产品SQL/协议/loopback及domain，不能替代Room/SQLCipher原生执行。

独立静态审查修正了两项发送边界：元数据桥内部直接调用持久 repository 的 `check/admit`，订阅不能替代授权；时钟取消在观察线程和同步线程保持同一终态。实际 JDK17 编译及 `javap` 已核验原6个业务入参、没有 caller-supplied gate 和公开任意文本认证入口。依赖注入不是进程内安全沙箱。

首轮新增13方法实际11通过、2失败：测试误要求过期时上层guard也为Cancelled，以及完整body读完后仍要求已经释放回连接池的socket关闭。修正保留原始HTTP Cancelled和owned socket关闭强断言：过期另断言guard拒绝及CONSENT_EXPIRED；比较流保持合法comment尾部未发送，socket仍由当前请求持有。第二轮实际12通过、1失败，原因是`TextDelta`使用对象身份比较；仅将它转为类型标记和完整文本逐项比较，不使用脱敏`toString()`，不合并或删除事件。失败日志/XML及各次输入指纹独立保留在 `.local-evidence/d04/active-http-revocation/`，不改写为通过。

两次失败XML的SHA-256分别为 `cda170b85473e37ea318467a7afdb70d7c0d8fc983e3609765a7d0a1d83ecd60` 和 `3842721fa2b3a2a884815dc917a35b430183cca9214e40f1d2408d292efa5233`。最后全套重新实际执行全部13方法，未沿用失败XML。旧14方法源码哈希仍为 `65e3670c0e322512e461007fea63ab798f7039245604810f5a1660c18c8a4e0c`，与接续基线一致。

迟到取消合同使用真实候选INSERT/COMMIT屏障：另一PID先读到COMMITTED及完整候选，取消已经进入真实 repository cleanup但等待owner返回，然后释放COMMIT响应，要求结果仍为Completed且行保持不变。该方法同时保留重复、冲突和缓存恢复零额外HTTP断言。

本地只读核验仍无 SDK、既有许可记录、adb、emulator 或 `/dev/kvm`。最小设备恢复动作仍是平台在唯一已保存配置下接入既有许可组件和已授权设备/受支持加速 AVD，先只读核验身份及状态；不启动设备/ADB、不生成密钥、不切换 HOME 或路径绕过只读限制。
