# D07 R2a：持久运行时合同与受控协调器

设计准入：父级已限定ACK `acdefd4983b5611ae8b878f5411fb105c863d18a` 的两项P2增补，
仅放行模型／领域、真实SQL／JCA与受控协调器。本片不是Android提醒接线或完整D07验收。
两份原方案保持原字节；LOCAL_ONLY、原文／候选／正式任务分离及单独确认仍有效。

## 实现行为

- `ReminderRuntimeReceipts.kt`定义不可变的event／claim／结果与闭合／退休事实；独立命名空间、完整字段摘要，
  同ID修改token／时间／版本／计划／能力或结果拒绝，ID不是执行授权。
- `ReminderRuntimeCipherFields.kt`实现实际有界v6字段读写；UserReceipt独立保留，RuntimeLedger不进入公开待办请求。
  v1–v5读取不改写；R1规范化请求及其编码helper与准入基线逐字节相同，另有独立Node固定样本验证。
- SQL在当前PENDING有精确SCHEDULE，或SCHEDULED时受理真实当前回调：原子消费SCHEDULE、写触发证明及DELIVER。
  不依靠重投过去alarm恢复。event／claim／结果精确重放只读返回认证当前状态，不依赖lastMutationId。
- 原event已受理、claim前能力丢失，同事务保存BLOCKED_CAPABILITY、消费DELIVER、进入DELIVERY_BLOCKED。
  原负面内存尝试先封口，事务回滚也不能改换可用快照续发；未知历史只显示未核实。
- notify前认领POSTING，仅原活跃内存尝试可以消费一次发布资格；生命周期结束不能由持久claim重建。
  发布调用返回只记NOTIFICATION_POSTED，不等于可见、送达或用户只看见一次。未知结果不自动再次发布。
- 槽上限4、每槽3072、ledger16384、密文65536字节；新确认前预留槽。当前槽及仍需核对的槽不驱逐。
  32条命令上限不变；当前DELIVER可原位转CANCEL，满槽仍能撤回／完成。
- CANCEL ACK等系统写保留运行时身份；单独、安全的门内清理才回收已退休、闭合、无未决命令的旧槽。
  普通恢复扫描不伪造触发；已知丢失的过去计划可记TIME_PASSED，真实当前大约迟到回调则可按原代次核对。
- `ReminderExecutionGate`和协调器贯穿核对→离开SQL→受控平台端口→结果写入；用户决定与关闭共享同门。
  owner封口后不准新租约，已准入调用真实结束前不能ACK关闭或抹key，失败关闭保持sealed阻止替代owner。
  没有访问资格时不调用仓库工厂，旧入口不改绑新owner；入口容量64、单次最多16、重投仅作有界核对。

完整备份已实际覆盖“持久DELIVER”和“已发布调用结果”两类源行：跨key恢复后全部RuntimeLedger／用户执行回执清除，
提醒REVOKED且无命令。来源创建回执及正式待办保留，不因原文回收擅自删除独立任务。

## 已实现范围与未接线边界

生产App没有实例化新协调器；原`DisconnectedReminderDriver`仍在，manifest、权限、依赖、Keystore alias及Room表结构不变。
App只补齐新枚举的诚实状态文案，保证编译和读取已有状态；没有授权入口、receiver、JobService、真实notify或冷owner。
前台VM／应用锁与新执行门的实际接线、首解锁检查、Android opaque入口封装、BOOT／时区／权限重建epoch，
均仍属于未放行的R2b。受控owner的NoAccess回归不等于真实Android锁屏／重启验收。

锁态无持久触发证明、进程死亡或认领后崩溃可能漏报；不会在解锁／重启后自动补发过去通知。
JVM上的SQLite/JCA与受控同步端口证明应用合同，不证明SQLCipher设备执行、OS／OEM调度或通知呈现。

## 因果覆盖索引

新方法实际源码见[SQL回归](../checks/ai-protocol/remote-text/src/test/kotlin/com/t1doo/aiyou/data/ReminderRuntimeSqlTest.kt)、
[执行门回归](../checks/ai-protocol/remote-text/src/test/kotlin/com/t1doo/aiyou/data/ReminderRuntimeGateTest.kt)、
[编解码回归](../checks/ai-protocol/remote-text/src/test/kotlin/com/t1doo/aiyou/data/ReminderRuntimeCipherTest.kt)。
下表对应设计的原16组及增补R/B10组，方法内多组参数不重复计数。

| 合同 | 实际回归落点 |
| --- | --- |
| P01 | SQL `pendingOnlyTriggerAfterSuccessfulScheduleAndMissingAckPostsOnceWithoutReschedulingPast` |
| P02／R01／R02 | SQL `eventAndClaimReceiptsSurviveUnrelatedCancelAcksAndColdReadWithoutGrantingAPermit`，对event与claim均注入COMMIT成功后丢返回，再推进独立CANCEL ACK |
| P03 | SQL `triggerTransactionRollbackAndUnknownCommitAreDistinctAndNeverBecomeColdPublication` |
| P04 | SQL早到／旧代次／错计划／缺行；Gate用户先提交终态与原文回收；Cipher认证／引用／EOF拒绝 |
| P05 | Gate `pastScanningDoesNotRescheduleAndKnownLostPastIsNotARealLateCallback` |
| P06 | SQL `triggerCapabilityFailuresAreDurableAndNeverSilentlyDowngradeExactMode` |
| L01 | Gate `noAccessAndOldIngressCannotReadTheRepositoryOrRebindToANewSession`，仅协议资格模拟，Android本体NOT RUN |
| L02／L03／R05 | SQL触发／认领后的冷重开，Gate `unknownResultWriteNeverRepeatsNotifyOrReconstructsAuthorityOnColdRead`，持久证明不重建许可 |
| L04 | Gate入口64容量、同token合并／旧owner拒绝、仓库工厂失败消耗入口重投资格 |
| G01 | Gate `schedulingAndExplicitRevocationShareTheGateAcrossTheExternalCall` 与撤回先提交零schedule |
| G02 | Gate发布中并发完成的latch顺序、用户终态先提交拒绝旧入口 |
| G03／R04 | SQL结果COMMIT成功丢返回→CANCEL ACK→精确结果读回；Gate结果写失败及过期authority拒绝 |
| G04 | Gate真实外部调用未结束时关闭等待／禁止抹key／后继owner等待，失败关闭保持sealed |
| G05 | Gate端口调用SQL深度0、回调只排队、递归取门拒绝；存储层故障注入均在独立事务结束后核对 |
| C01／R06 | Cipher实际4槽＋32命令＋最大合法Unicode输入写入／读取后，以真实SQL完成并撤销32命令；SQL容量拒绝与独立清理 |
| R03 | Cipher全字段同ID变更冲突，跨任务与清理后变更拒绝；Node独立固定摘要／ID |
| R07 | 独立v1–v5固定密文、R1摘要字节、v6错误引用／阶段／版本／原因／EOF／大小，跨key portable及完整备份恢复 |
| B01 | SQL可用→认领前不可用→恢复，持久DELIVERY_BLOCKED、notify0、DELIVER无复活 |
| B02 | SQL受阻claim回滚后只重试原负面请求；Gate持续写失败后冷开保守清理，notify0 |
| B03 | SQL GRANTED后调用前能力丢失，认证NO_ATTEMPT_CAPABILITY_LOST，notify0 |

## 复核修正与证据

保全了初轮新增DELIVER后尚未补SQL when分支的编译失败、容量测试未先改未来日期的交互失败，
以及原文回收测试尚未经产品读取建立访问状态的准备失败；均有明确因果修正，没有放宽产品校验。
两次中间完整主机回归1298／1299通过结果分别归档，不混作最终计数。

复核另修正了两项边界：工厂失败也消耗入口重投资格；新入口得到ALREADY_OBSERVED时不能按token封闭别人的event。
后一项先以真实SQL单项复现密文字节变化，再将finally清理限定到原event ID；15项Gate方法随后全通过。
原设计2项基线探针的预期失败继续保留，不把它们算入新产品通过数。

最终完整命令／XML、同轮输入指纹、实际变更源字节、日志及历史收据将保存在
[R2a主机证据包](evidence/d07-r2a-runtime-host-20261020.zip)。
最终主机以`--rerun-tasks`实际执行1300项唯一方法：AI178、领域71、remote-text1051，失败／错误／跳过均0；
同轮输入指纹`725c87f5212daeb2ba07dabdb0bb85fac4ab9cb417f9f118932880a2535118e8`。
新增33项(SQL11／Gate15／Cipher7)，另扩展既有完整备份方法；参数组合不额外计数。
主机运行时HEAD为准入设计基线acdefd4983b5611ae8b878f5411fb105c863d18a，执行本轮工作树字节，
归档保存实际变更原件与指纹；不冒称主机是在稍后的新提交SHA上执行。
精确提交的Android CI另以最终交付收据为准；本地无已接受许可的SDK，未安装／接受新协议。
设备、真实权限、闹钟、通知及R2b均NOT RUN；没有模型真实调用或新费用。
