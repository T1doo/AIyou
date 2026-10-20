# D07 R2a：drain 两项独审修正

父级对 `277ab40f4a60d23cbaec92cd2e281b75c69d5cc4` 的独审指出两个确定 P2：
先 take(limit) 后跳过过期 SCHEDULE／DELIVER 会耗尽执行批次；能力查询跨过 due 后仍可安排过去 alarm。
本次仅修改受控协调器及其真实 SQL／受控端口回归，不接入 R2b，不改变 v6、回执、恢复、owner 准入或容量合同。

## 排空合同

- 在原有的一次 `reminderCommands()` 快照中先筛选 CANCEL 和当时仍未来的 SCHEDULE，再选择至多 limit 项。
  DELIVER 与过期 SCHEDULE 保留原字节，不占执行批次，不因此退休或伪造触发／ACK。
- 按完整 token 稳定排序，以同一 owner 内一个 token 游标轮转。失败且仍未决的命令不会每轮永久占住前排；
  同 owner 新建协调器仍接续游标。游标只有固定一个 token，不持久化，不是回执、访问资格或发布许可。
- 每轮仍只取得一次既有存储快照，不为过期项增加逐项 SQL 查询；至多 limit 次 current 核对与平台尝试，limit 仍为 1..16。
  该存储 API 原本读取全部未决命令，本次没有把它改为分页扫描，也不声称只扫描 limit 条存储行。
  分类后排序的数据量不超过原快照；没有内部无限排空或重试、额外每任务缓存或容量扩张。
- 公平性限于仍活跃 owner 内有限稳定集合的后续调用；owner 退休后不迁移游标，不承诺无限新增工作下的固定等待时间。
- SCHEDULE 在能力查询前核对未来时间，查询结束后、真正调用 schedule 前再次核对 due > now。
  若查询已跨期，保留原 PENDING 与命令，不调用 schedule、不写能力／成功 ACK，真实当前迟到回调仍可按原代次受理。
  这是调用前的时钟观测，不是 OS 调度／通知送达保证。

## 因果回归

新增方法均在 `ReminderRuntimeGateTest`，使用生产 `SqlManualTaskRepository`／JCA 和真实 SQLite fixture：

| 方法 | 断言 |
| --- | --- |
| `expiredSchedulesAndResidualDeliveryCannotStarveFutureSchedulesOrCancellations` | 20 条过期 PENDING 加一条残留 DELIVER 前置，在 limit=1／16 时未来 schedule 与 CANCEL 都能推进；每轮一次快照、至多 limit 次核对／端口；原过期行及 DELIVER 密文字节不变、DELIVER 未闭合、notify0 |
| `failedEligibleCommandsCannotStarveLaterWorkAcrossCoordinatorInstancesForTheSameOwner` | 17 条持续失败的未来 schedule 与后置未来 schedule／CANCEL；同 owner 每轮新建协调器，在按 limit=1／16 计算的有限轮次内后两项推进；失败命令及原行保留、端口预算不扩大 |
| `capabilityQueryCrossingDueCannotScheduleOrAckPastAlarmAndLateCallbackStillWorks` | 受控能力查询把时钟从 due-1 推进至 due 后返回可用，schedule0／ACK0／原密文不变；随后同一原代次的受控迟到回调仍可受理并仅尝试一次发布 |

先只增加这三项方法：旧实现实际执行 3 项、失败 3 项，失败日志及 XML 保全。
修复后 Gate 全 18 项通过，失败／错误／跳过均 0。
最终完整命令 `python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --rerun-tasks`
实际执行17个任务，4分53秒完成；严格源码方法／XML核验1303项：AI178、领域71、remote-text1054，失败／错误／跳过均0。
同轮输入指纹 `dace939998a20c95e9a081403e5067753776f85cd869ea2a7e364c8e8eaf3b3f`；
运行时HEAD为旧基线277ab40、执行修复后的工作树字节；不冒称主机已在稍后的修复提交SHA执行。
脚本168项、生成token与固定HTTP依赖字节检查均通过；
[本轮证据包](evidence/d07-r2a-drain-p2-host-20261020.zip)保全实际修复源字节、最终XML／输入收据、红绿日志与旧CI元数据。
原主机 1300 项通过的证据继续保留，不与新增方法重复计数。

## CI 与边界

旧 CI [39395946369](https://github.com/T1doo/AIyou/actions/runs/39395946369) 已最终 success，
精确对应旧提交 `277ab40f4a60d23cbaec92cd2e281b75c69d5cc4`；必需步骤成功，条件步骤18跳过，16项产物元数据可核。
这些结果不能证明本次修复。原始日志／APK下载拒绝未重试；CLI 的401已保全，元数据通过现有授权 GitHub 连接读取。
修复的精确 SHA／CI 及最终主机收据以本次交付为准；父级复审尚待完成。
本地没有 Android SDK／设备；JVM SQLite 与受控端口不等于 SQLCipher设备、锁屏、OS／OEM提醒或通知验收。
