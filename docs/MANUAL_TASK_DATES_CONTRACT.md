# 手工待办日期、时刻与延后

后续提醒模型扩展见 [F06/D07 提醒模型](F06_REMINDER_MODEL_STATUS.md)：在下述既有
日期 CAS 基础上另行明确确认提醒，日期变更撤销旧提醒版本；仍未接入系统通知或闹钟。

本片对应原始方案 F06、§8.2、§13.1 及 D07 中的手工计划部分。用户可以明确填写日期、可选时刻和 IANA 时区，并对仍为 OPEN 的固定任务设置、修改、延后或清除计划。标题中的“明天三点”始终是标题，不触发解析。AI 候选确认、系统通知、权限申请、闹钟、Worker、后台监听和终态重开均未加入；完整 D07 仍为 OPEN。

## 时间含义与用户确认

- 日期使用 `YYYY-MM-DD`，时刻使用分钟精度 `HH:mm`，时区独立于创建时区保存。仅日期没有 instant，不补午夜、09:00 或其他隐含时刻；仅时刻而无日期不能提交。
- 计划时区必须是运行时可识别的 IANA 名称，不能用固定 `+08:00` 替代。夏令时 gap 拒绝保存；overlap 必须明确选择第一次或第二次，保存实际有效的偏移秒数。旧歧义选择不会沿用到不同的日期、时刻或时区组合。
- “修改”可以改变日期、时刻、精度或时区；“延后”要求与旧计划的时区和精度相同。仅日期必须改成更晚日期；带时刻的计划必须对应更晚 instant，包括同一重复本地时刻的第二次出现。无计划不能延后或清除。
- 仅 OPEN 任务可以操作。所有动作绑定 ID、完整任务版本和编辑会话；同名任务不构成目标授权。完成、取消保留计划，均不开放重开。
- 列表显示未设时间、过去未完成、今天、接下来、已完成和已取消。日期型在保存时区的次日才进入过去；定时型在指定 instant 之后进入过去。终态优先。每 30 秒刷新分类只影响显示，不构成调度或通知。

## 持久化、兼容与回执

沿用现有 Room v9 / SQLCipher owner 和四列 `manual_tasks(id, version, lastMutationId, ciphertext)`，不更改数据库 schema、原有迁移或历史导出，不引入依赖、Android key alias、权限、凭据或网络路径。

旧 v1 封装仍用原 key derivation、`AIyou manual tasks v1` AAD、字段顺序及严格 EOF 校验。旧字节读取为无计划，不因读取或精确重放而重写；认证后多出的字节也必须拒绝。新写入使用头字节 2 和独立 v2 AAD，加入操作类型及显式日期/时刻 presence、时区和偏移。v2 同样限制长度、严格解析和 EOF；认证失败不尝试另一种格式。两版沿用现有任务子密钥的派生域，随机 nonce 和关闭排空／擦除所有权保持原状。

设置、修改、延后、清除与完成／取消共用整条任务的版本 CAS。一项成功后，持旧版本的另一项只能冲突，不能自动刷新版本再执行。最近一次日期回执精确比较 `expectedVersion+1`、mutation ID、操作、完整计划和原请求时间。未知提交结果保留同一个不可变请求；重试不创建新 UUID、不重新取当前时刻、不重新加密已提交值。只有真实事务及认证读回后显示成功。回滚、冲突、损坏和读回失败不显示保存成功，也不覆盖已有密文。

## 界面与生命周期

生产 `EncryptedEntryRepository` → `ManualTaskRepository` → 实际 ViewModel IO 队列 → controller → Compose 使用同一条路径。私有未确认日期、时刻、时区与标题使用直接 remembered `TextFieldState`；字段下禁用 SaveableStateRegistry，输入及 Undo 不进入 Android Bundle。配置重建可以继续依附现有 VM 内存；进程死亡或明确关闭不承诺恢复未确认输入。

关闭未知回执编辑器只关闭编辑会话，保留稳定 pending。旧页面、旧 session、旧版本以及 dispose 后回调不能提交到新目标或发布迟到界面结果。导航入口沿用同步 `ownsTasks()` 检查；不会等待一次重组才撤销权限。失败读取与空列表分开显示。

## 验证边界

Host 使用实际产品 SQL/AEAD、真实文件 SQLite 和独立连接，覆盖旧字节冷重开、认证后尾部垃圾、date-only、DST gap/overlap、日期与完成/取消 CAS、事务回滚、丢 ACK、精确重放、分类和实际 VM 队列／生命周期。Compose 使用实际路由／controller 及受控回执端口，验证确认、字段隐私、业务回调失效和真实 320×640 窗口／200% 字体；它不证明持久化加密或物理设备行为。

旧 v1 冻结 fixture 由独立 Python `cryptography.hazmat.primitives.ciphers.aead.AESGCM` 生成，未调用生产 `seal`。旧 serializer 的 UTF-8 字段使用 big-endian int 长度，AAD ASCII 字段使用 Java writeUTF 的 big-endian unsigned-short 长度；时间和版本为 big-endian long。合成数据库 key 为 host 41..72、native 43..74，nonce 为 7..18；ID=`legacy`、version=1、mutation=`legacy-create`、title=`legacy literal 明天三点`、status=OPEN、创建/更新/请求时间=55、时区=Asia/Shanghai。旧派生域和 AAD 按冻结 v1 原值独立构造。两份原始密文均 109 字节：host SHA-256 `130328ca487ad820c0baa0ad7f371a8c56fc12391d3175263c1213d28aac606a`，native SHA-256 `4d13b9cfd06f0c2a52fc3b2cbdd2c5e98cf985fbd9afa2eace7a7eac52286dee`。这些固定字节仅是合成测试材料，不是产品密钥或运行时 nonce。

四个既有正式 native 方法增加日期、原 v1、终态保留计划、原生事务回滚及日期/完成竞争断言；方法名称和原生总清单不变。APK 编译与 DEX 收集只能证明测试存在，不能证明测试通过。最终结果必须绑定实际完整 SHA、输入指纹及原始 XML；定向 30 方法不替代完整回归。设备、生产重启、Keystore、原生迁移/执行、16 KB、TalkBack、OEM 和性能仍 NOT RUN；D02/D04/D06/D08 的既有缺口保持 OPEN。
