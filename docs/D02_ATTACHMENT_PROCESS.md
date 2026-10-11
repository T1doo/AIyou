# D02：附件文件协议的独立 JVM 进程验证

本文记录 `2a7ce63` 阶段的进程验证范围与数量。后续已授权 PNG 界面连接及新增检查见 [D02_ATTACHMENT_UI.md](D02_ATTACHMENT_UI.md)；本文件下文的“没有附件UI”描述该历史阶段，不代表当前工程能力。

此验证使用现有JDK17与固定host工程，Sync编译真实生产`EncryptedAttachmentStore`，不复制/重写Cipher、文件锁、认证或发布算法。没有新增第三方依赖、SDK、模型、凭据、网络权限或附件UI。原方案和生产源码不因写测试而改动；如实际发现生产缺陷，应先保留红证据再修正并重新独审。

## 进程与故障边界

父JUnit进程用`ProcessBuilder`启动`java.home/bin/java`，显式传入Gradle test runtime classpath。每个child的实际`Process.pid()`须与child自身`ProcessHandle.current().pid()`和记录的父PID匹配。固定64KiB合成输入只在内存生成，固定测试key只在测试代码/内存，未写key文件或输出源字节。生产保存/读回/恢复都在child执行，父测试不借用同PID的两个实例代替跨PID。

父进程仅持有自己刚创建的`Process`对象。只有收到这个PID在预期阶段的原子标记后，才调用该对象`destroyForcibly()`；随后有界wait确认非零退出、`isAlive=false`且没有caller result。每次启动/阶段/终止请求/实际退出都写JSONL并进入JUnit system-out。发生断言错误也只清理该自有child，保留场景文件；不枚举、杀未知PID，或删除任何已有锁文件。阶段等待最多10s、child自有暂停最多20s、退出等待最多10s；没有无限等待或扩张旧TCG超时。

持久账本是清楚标注的单记录测试适配器：元数据原子替换、文件及目录sync、意图比较。READY确认丢失点位于此**合成账本已持久化READY、transition尚未返回**之间。它不证明Room、SQLCipher或Android Keystore跨PID；父对metadata的改写仅在确认自有writer退出后，为明确的篡改fixture。文件协议仍是未经替换的生产实现。

依据JDK17 [Process API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Process.html)，终止请求与实际退出分别核验；不把`destroyForcibly()`返回当成退出证据。跨进程非阻塞锁依据[FileChannel.tryLock](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html#tryLock())；该测试覆盖遵守此生产协议的两个自有进程。

## 五项真实回归、十五场景

| 回归 | 实际要求 |
|---|---|
| 独立PID争锁 | holder到STAGED仍持有生产锁；另一PID的recover和save都精确拒绝AttachmentBusy，未读source、账本及密文不变。holder被终止且退出确认后，第三PID恢复；另一个PID的变更意图被拒绝 |
| WRITING三边界 | intent尚无文件、writing已有不完整密文、encrypted已完整密文但未STAGED；终止后不同PID恢复为FAILED，禁止读取成功，仅显式同意图提供来源重试后READY |
| STAGED与published | 终止前保存密文hash/size/file-key/mtime，新PID验证后READY及读回完整输入；同一密文/文件身份未被重写，不重新打开来源，不保留重复partial |
| READY提交确认丢失 | 合成账本已经READY，writer没有返回caller result；终止后新PID只验证/读回，文件及稳定ID不变，无来源调用 |
| 七篡改与错误来源 | STAGED writer退出后，改附件ID（同时改候选文件名以实际走AAD认证）、原文源ID、revision、hash、MIME、tag或key；不同PID均CORRUPT、不释放plaintext、不发布、不覆盖证据。另一个child读到错误源字节必须FAILED而非READY |

完整套件当前50项=原28文字队列+17附件协议+5进程回归。5进程方法内部15场景实际启动31个独立child PID，14个明确自有writer受强制中断、31次退出确认，7次新PID完整认证读回、7次新PID篡改拒绝及1次错误来源拒绝。数字不是预签验收：必须以本轮真实XML、事件和独审报告为准；测试写完/编译或NO-SOURCE不能当执行。后续变更若调整协议，应同步具名/事件守卫并以新冻结源码复核。

执行入口：`AIYOU_TOOLCHAIN_ROOT=/workspace/toolchains scripts/gradle.sh -p checks/record-queue test --offline --rerun-tasks`（按主机实际已配置路径设置，不下载新工具链）。常规CI也执行此host套件。`scripts/verify-test-results.py --queue`核50真实case、17原附件和5进程具名检查，严格核15场景/31PID/14终止/31退出/来源次数及已发布文件hash/file-key/mtime。现有reports artifact追加`checks/record-queue/build/reports/attachment-process/`，含本轮合成fixture、JSONL、stdout和阶段/result标记。既有53 App测试、320render、严格lint、schema2/3与依赖锁合同保留。

事件守卫仅在全部真实XML/进程合同通过后，逐行输出其中的`D02_ATTACHMENT_PROCESS_EVENT`到CI日志，便于直接复核实际PID、阶段和退出确认。来源是本次JUnit的system-out，不构造伪PID或回放另一次测试；JSONL与原XML仍随reports artifact保留。事件只有角色/阶段/PID/状态/合成hash/文件属性，不输出源字节、key或用户路径。

## 尚未验证

这只证明所运行的JVM/主机文件系统上的明确进程边界，不是物理断电、Android目录持久性、Android跨PID或完整密码学审计。原13与新增4项Room/SQLCipher/Keystore原生测试、真旧4.19.0库升4.19.1、Android跨PID、实际16KB/性能、生产UI进程死亡/IME/TalkBack及精确JNIString NOTICE祖源继续保留各自NOT RUN/UNKNOWN。没有附件界面导入功能，D03未启动，完整D02未验收。
