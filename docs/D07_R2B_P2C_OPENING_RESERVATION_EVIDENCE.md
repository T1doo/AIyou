# R2b OPENING reservation 释放修正（待最终独审）

最终产品 `3e8727bffb02aa141e45f792661573bc9320ffc6`，基于上一包交付 HEAD
`e4355e2619a85c32e8070b569ea4132be552aae8`。`b705a93`、`8f61e0d`、`f6be779` 是保留的中间产品；
旧归档保留原字节。本轮只修改生产 `ReminderProcessOwner`、`ReminderOperationBridge` 和新增边界回归，
没有变更 Job 终结主体、权限、SDK 或外围功能。主体已修不等于实现 ACK；仍不推送、不启动 CI。

## 归属及关闭证据

OPENING 先登记唯一的对象 reservation；`prepareFront` 在排入 worker 前认领该身份。
认领前在同一 guard 内检查调用方仍活跃；已取消旧调用方不能改变当前 intent、seal 新 owner 或退休新票据。
正常 dispose 取消该 reservation，退休旧票据并 seal 当前 owner，不直接猜测物理关闭。
worker 准入谓词、factory 开始前、返回 store 后及发布 Pair 前都核对当前身份与取消/owner 状态。
factory 开始前的原子登记是调用归属点；其后到达的取消不推导成调用已撤销，等待实际返回和关闭。

尚未认领的 reservation 仅向原单线程 executor 排入状态观察；没有新增线程池、后台组件或 IO。
已认领但未进入 factory 的失败准入由 worker 确认本次没有获得 owner、没有物理 open 后登记 settled。
此证明属于该 reservation；即使旧 holder 尚在，也保留证明而不关闭或释放旧 holder。
只有当前身份仍匹配、已经取消且 settled、没有 holder、phase 仍为 RELEASING 时才回到 NO_FRONT。
已开始 factory 的 store 在同一不可取消 worker 尾部实际 close/drain 并清 holder，才产生关闭确证。
关闭未知继续保留 sealed holder/native handles/key，不能启动第二次 factory。

新 intent 若尚未认领，或已认领但证明未 open，而旧前台或冷 owner 仍存在，先保持 RELEASING。
旧 owner 的实际成功关闭只清除其自身 holder，再为当前已可证明结束的 intent 排入新的 worker 观察；
旧回调不直接清除新 reservation。当前已有 owner、身份变化、显式锁或 UNKNOWN 均阻止这次释放。
旧票据不能迁入新的冷准入；冷路径仍重新读取严格启动偏好。

| reservation 阶段 | 允许的收尾证明 |
| --- | --- |
| 未认领 | 同一 worker 观察当前身份仍取消、未认领/未 open 且无 holder |
| 已认领但未 open | 准入退出后 worker 登记该次未获得 owner；若旧 holder 还在，继续等待其实际关闭 |
| factory 已开始或资料已准备 | seal 后等待实际 close/drain；成功才能登记 settled，失败保全资源/key |
| 已取消的旧调用方尚未认领 | 直接传播取消，不修改当前 owner/intent |

## 先红后绿与实际测试

| 实际执行 | 结果 |
| --- | --- |
| 旧 e4355e2 的三类定向回归 | RED：12 项中 3 失败：worker 准入前/准入中释放后永远没有冷候选；factory 内 dispose 返回 Pair 而未关闭 |
| 新增未调用 prepareFront 的未认领阶段 | RED：1/1，释放后仍没有冷候选；修正后纳入最终回归 |
| 新增未认领 intent 等待前一 owner 关闭阶段 | RED：1/1，前台 owner 已实际关闭但新 intent 永久滞留；修正后覆盖前台、冷 owner 与关闭未知分支 |
| 新增已认领但未准入 intent 等待旧 owner | RED：1/1，该次未 open 的证明因旧 holder 存在而丢失；旧关闭确认后仍滞留 |
| 新增已取消调用方进入 prepareFront | RED：1/1，旧调用方会先 seal 当前 owner；修正后当前 owner/票据保持有效且 factory/业务零调用 |
| 最终 `3e8727b` 三类受控原生测试 | PASS：16 = reservation 9 + 生命周期 3 + factory/取消/关闭失败 4；30s；失败/错误/跳过均 0 |
| 最终 `3e8727b` 的 `ReminderProcessOwnerTest` | PASS：5；18s；方法/XML/输入指纹核对通过 |
| `b705a93` 的 `ReminderOperationBridgeTest` | PASS：3（与 owner 5 同轮共 8，34s）；该 bridge 和测试源码逐字节等于最终产品，未为无变化的 bridge 重跑套件 |
| 主机 Room KSP v15、生产源码副本、XML 方法清单 | PASS；导出/提交/打包合同字节一致，所有最终 native 输入等于受测完整 SHA |
| 其余受控原生 25 项、主机全量/队列/Python/tokens | 本轮 NOT RERUN；旧结果保持历史证据，不改称当前全量通过 |
| Android APK/AAR metadata/lint/Compose/Preview、CI、真实权限/通知/闹钟/重启/OEM/设备 SQLCipher/16 KB/性能 | NOT RUN |

原生命令为 `python3 scripts/jvm-gradle.py -p checks/reminder-native :test`，过滤
`ReminderOpeningReservationTest`、`ReminderFrontLifecycleTest`、`ReminderFrontFactoryFailureTest`。
主机命令为 `python3 scripts/jvm-gradle.py -p checks/ai-protocol :remote-text:test`，过滤上述 owner/bridge 类。
全部 Gradle 串行执行；每次重跑对应新增失败边界或后续生产改动，不运行重复 CI，也不重复无变化全量套件。

边界包括 worker 排队前、持有准入门时的解锁观察、factory 中、真实 Controller 等待初始读取时的普通释放；
以及显式锁/未知访问/未知关闭、晚到旧 continuation 与新 owner/票据、尚未 prepare 的 intent、已认领但未 open 的 intent 与旧 owner 关闭。
已取消旧调用方的回归另保留一份同名异常导入的编译失败日志；该日志不是语义 RED，修正导入后才复现上述断言失败。
使用实际生产 bridge/gate、实际受控 SQLite 和合成 key；不能据此声称设备 SQLCipher 或真实平台已验收。

最终 native 指纹 `0238e923b5145af997efe07b43a26aefc1c2ccbeafc520b946767023d1331a4c`；
最终 owner 主机指纹 `715e6828b273acaad007d3502ad1e667e8d19eb1c5ca15a260a80dd5949f34b3`。
[最终证据 ZIP](evidence/d07-r2b-p2c-opening-reservation-final-20261020.zip)：144161 字节，61 项，
SHA256 `20e94a90986be9b9efeb6300352b892fc9d69be44011679ff3c5e58ecdda175b`；逐项哈希/CRC 已核。
原 [8f61e0d 中间归档](evidence/d07-r2b-p2c-opening-reservation-20261020.zip) 保持原字节，不改称最终证据。
`final-native`、`final-host-owner` 属于最终产品；`host-targeted` 保存上述不变 bridge 的证据；其余日志/收据均为中间过程。

普通 Git 只读确认远端开发分支仍为 `9072bb941000a5956c9229bb29b9372657aa8c70`，
main 仍为 `7383e502418f5f9fc0c540ef2072742359343d40`。产品领先远端 13、落后 0；后续证据提交不改产品。
保持 LOCAL_ONLY、无自建后台/真实模型调用，无新费用/凭据/权限/SDK 条款接受，无 Azure 拒绝下载重试。
原方案文档未改；D03 ASR/OCR、D04 绑定和 D07 整体验收仍 OPEN。等待父级对本更新包最终独审。
