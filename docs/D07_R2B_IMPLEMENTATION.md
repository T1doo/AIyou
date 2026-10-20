# D07 R2b 原生提醒接线候选

按 `9072bb941000a5956c9229bb29b9372657aa8c70` 的父级限定设计 ACK 开发。
[R2a 封存](D07_R2A_CLOSURE.md)及[原生设计](D07_R2B_NATIVE_WIRING_DESIGN.md)仍为合同；
本文件记录后续实现，原设计的“尚未实施”描述是设计时点事实。
完整遵循原 Master Plan、Product Architecture；本片不等于 D07 完成，也不把 D03 ASR/OCR 或 D04 绑定称为完成。

## 实现边界

- 唯一进程 worker/执行门覆盖实际 VM 的 ManualTasks、来源/提案转正式任务及 RecordPackage 恢复。
  挂起动作可以跨 IO 线程，但锁仍由同一物理 worker 持有；拒绝子 IO/同步 callback 等待自身 worker。
  controller 已移除内嵌 dispatcher；用户提交完成后另排运行时命令。
- AppAccess 在 OPENING 准备唯一记录 store、注入 VM、等待初始读取，再发布 OPEN。
  已拥有的 store 在构造时交给 VM；actor 以 ATOMIC 进入 try/finally，避免初次 IO 尚未派发就清理造成泄漏。
  锁定/强制清理封口新票据，真实操作、关闭及 key wipe 完成后才确认 appAccessClosure。
- 冷路径仅读严格启动偏好，要求首次解锁和保护关闭；使用既存文件、既存 key、SQLCipher 无 CREATE 的打开路径。
  不创建完整资料 VM、不迁移、不修复 schema、不使用完整工厂兜底；校验实际 Room 定义、identity、字段/default/PK/FK/index 和必要 guard。
  工厂或关闭结果未知时保留 sealed owner 与隔离资源，阻止替代 owner；不把抛异常当成关闭 ACK。
  SQLCipher 原始异常/私有路径只输出固定状态，测试/CI 失败日志仍保留。
- 新增 Room v15 单行加密 meta：JCA purpose 隔离、4096 字节 frame 上限、CAS/未知 COMMIT 读回。
  固定上界按 SQLite BINARY 分页，每页最多 16 行/16 命令；同一任务的 32 命令按 numeric generation 继续。
  新一轮从空游标开始，未完成轮次恢复游标，dirty 只增加一轮有限扫描；无需环绕即可满足至多一圈的有界合同。
  过期 SCHEDULE/DELIVER 不占执行名额；坏行及失败前缀会推进，停止中途不 checkpoint 未完成页。
- AlarmManager 使用 RTC_WAKEUP：EXACT 调用 setExactAndAllowWhileIdle；用户明确选择的大约模式才调用 setAndAllowWhileIdle。
  最后一次 due 检查位于能力/封装/PI 准备之后；跨期不调 set、不伪造 ACK、不撤销原合法迟到 PI。
  通用通知只含固定打开提示，CLICK 与 ALARM 使用不同 AEAD 用途和完整 HMAC URI 身份；单 token 撤销两端均返回才 ACK。
- 实际 receiver 只做尺寸/URI/内存登记及两秒交接；JobWorkItem 只有 process nonce + ticket。
  64 个唯一身份票据，重复身份复用；每次让出前至多 16 次 dequeue、一页 SQL、共享最多 16 次 post/schedule/cancel 端口调用。
  非 persisted 固定 JOB_ID 在进程第一次新 enqueue 前有序清旧项，不碰其他 job。
  onStop 退休尚未开始的尝试；已开始的调用可返回并记录原结果，不能被取消推导成“未执行”。
- 已知 boot count/版本/时区/能力摘要不变时不重建新 epoch；未知 boot count 的保守重建标记由进程共享，避免每次冷 owner 重开重复重建。
  TIME_SET 退休旧 alarm 票据并请求未来计划重建，不批量写 TIME_PASSED，不改变保存的时区/计划。
- 通知点击只走当前 AppAccess 下的正式待办导航；一次内存请求在取消认证、新点击、会话退休、销毁时丢弃。
  经当前 VM/门解封并重读 OPEN/当前 token，返回 UI 前再核对 session/版本/restore generation；点击不写 task/event/result。
  权限按钮核对当前 lease、editor 和 route；系统返回只重新观察能力，不当成授权、不重复用户提交、不自动降为大约提醒。
- 全备份排除 meta，跨 key 恢复清 reminder/runtime，保留既有 R1 用户回执语义。
  Manifest 仅新增 POST_NOTIFICATIONS、SCHEDULE_EXACT_ALARM、RECEIVE_BOOT_COMPLETED；无 INTERNET/USE_EXACT_ALARM/网络状态/前台服务权限。

## 可复核测试及局限

新增 `checks/reminder-native` 使用项目已有固定 Room/KSP/SQLCipher/SQLite/coroutines/Robolectric 版本。
它编译实际完整 data 与原生 receiver/service/adapter/broker；只有未被运行的 Activity 导航目标和 R 符号是壳。
测试使用实际 SQLite、SQL/AEAD、实际 JobWorkItem 与组件，OS 队列 Binder/平台效果端口受控。
API26/31/33/37 是在固定 API35 JVM SDK 上的条件模拟，不能称为这些系统版本实际运行。
独立 Node crypto fixture 与真实 JCA 交叉核对；v14→15 迁移执行实际 SQL，旧导出逐字节保留。

Room 主机导出先用基线源码生成 v14，与已封存文件逐字节一致（SHA256 `379806d299ae66d9a74eb0a8c9aa67f255024323399d3801635b7085cfd65ed8`），
再处理实际 v15 源码。v15 identity `209962b51ad9ef0eedafacf24c128e11`，导出/打包冷合同 SHA256
`17f0147800135c58fa5dbcda2c5ffa55bbabcde773cb822805e524105f800698`，41571 字节。
新增 CI 前后 capture/verify 要求实际 Android KSP 与提交及打包合同一致，原 v2–v14 不变；不手写或修补生成定义。

最终执行收据见本轮 evidence；中间编译/测试失败均保留，修正针对已确认的 API、JSON 缺省字段、fixture 或生命周期原因。
完整 Android AAR/APK、lint、Compose/Preview 仍需精确提交 CI。真实权限、通知、闹钟、OEM、重启、首解锁、设备 SQLCipher、16 KB 与性能均为 NOT RUN。
没有安装 Android SDK、接受新 SDK 条款、真实模型调用、部署或上架。
