# D03 短原音：无权限受控实现候选

接续父侧限定设计 ACK `c597754b6979608db4ed8cd584e566fcdd8e2351`。
首轮实现未获ACK，父侧发现四项P2；修订产品及其精确验证见[四项P2修订说明](D03_CONTROLLED_AUDIO_P2_REVIEW.md)。
下列1358/41项是首轮候选输入的历史证据，不替代后续修订或实现独审。
本片没有实际麦克风/AudioTrack adapter、录音按钮、系统授权或 ASR/OCR，引入的输入端口仅供受控合成样本与后续原生适配。
它不是 D03 完成、真机验证或可用录音功能发布；原设计的许可边界继续生效。

## 变更合同

- `CaptureDraftGate` 与同一 records owner 的草稿、legacy 草稿、选择包/完整恢复和目标永久删除共用。
  UI 变更决定与命令接收处在短同步边界；每个已接收草稿命令带不可持久化资格进入 actor。
  开始先冻结，排空旧编辑、检查完整快照及 UI/持久 text，再保留最终版本；同一 actor 内不等待后续 actor ACK。
  实际进行的存储操作另计数，过期/关闭/退休资格不能准入新写，未结束的旧操作不因 scope 取消而被算作排空。
- package 命令从接收至实际 finally 单独计数，防止面板已关闭但恢复仍在执行时开始录音。
  数据恢复/删除在实际入口再检查同一门；UNKNOWN 期间不允许编辑、增删附件、清空、范围改变、通用 recover 或旧回调提交。
- `ControlledCaptureAudio` 在独立 IO 协程使用有界合成端口，actor 不等采样；结束、取消和退休一次结算。
  正常完成通过原 actor 暂存；强制退休在 actor finally 直接等待实际释放并收尾，禁止向已关闭队列 enqueue 后等待自身。
  设备释放未知保留句柄和冻结状态，不开启替代输入、不伪造关闭 ACK。
  打开资格标记与 stop/cancel/retire 在同一 monitor 排序；就绪谓词的旧观察不能越过已赢的退休。
  标记前退休不调用端口；标记后才退休允许已准入 open 真实返回，随后立即停止/释放，关闭仍等待该 job。
- 固定 44100 Hz/mono/16-bit PCM、60 秒，canonical WAV 最大 5292044 字节；独立 RIFF 字节夹具核对 little-endian/长度/EOF。
  不写明文 temp/cache/MediaStore，不新增数据库、key purpose、alias、依赖版本或安全权限。
- 草稿增加独立音频 intent/state。写入只接受保留 base 的确切 WRITING/READY 后继，其他字段和版本推进不能冒充自己的回执。
  文件、哈希、大小及 WAV 均验证后才 READY。结果未知保留同一 intent 和内存波形；只读证实未写入也不自动丢弃，需重试或明确放弃。
  未确认设备/存储收尾时关闭回执异常，不将本段称为已保存；未持久 RAM 不具有进程死亡恢复保证。

## 兼容格式与可迁移性

- 草稿 format 3：显式 policy + 原 1/2 结构 + 唯一音频 id/size/SHA/state；固定 audio/wav 类型。
  没有音频时仍写原 1/2 字节。旧格式不宽松接收追加字段，当前 codec 的 EOF 检查保持严格。
- 选择包 magic `AIYOUPK9`，version 9：认证元数据含类型化音频描述符和原 2–7 scope 元数据。
  原 history/人物/事件日期/已保留编辑草稿/任务范围沿用，不为音频削减；完整帧认证、terminal 与 EOF 后才放行。
- 完整备份 magic `AIYOUFB9`，逻辑 `AIyou-logical-full-v9`：图片和音频分列描述符，同一总量上限与现有 KDF/AEAD。
  v9 恢复回执使用 `FULL_BACKUP_IMPORT_V9` 与 revision 9；同一备份重放核确切 digest/回执，不重新安装。
- 含音频的内部选择包导入 journal 使用单层 format 6 封装原 2–5 范围布局；旧 journal 仍只接受图片。
  新封装禁止嵌套、截断、额外字节和无音频冒充；不扩大原 32 KiB 上限。
- 选择包恢复新建身份；完整备份维持既有 F08 空目标逻辑身份/回执合同，当前设备重新加密并清运行资格。
  正式附件与音频草稿均进入加密备份；完整目标不能把仅音频草稿当空白。
- 图片与音频用独立类型，不改称图片数量；预览在有音频时另列计数/字节。
  纯原音允许按手工标签进入原文搜索，但不产生转写、关键词或图片命中。
  事实回顾保存独立音频清单证据，发布时连同状态重新核对；原音不阻断其他原文回顾，也不被解读为文字或图片。
  当前开放图片 ZIP 不支持音频，遇到音频明确整体拒绝，不静默遗漏原音。
  原文两份方案、Manifest、Room v15 schema 和生产依赖版本未改。

## 验证状态与未验边界

首轮候选提交 `c9f561f24ffd0e0aa75f9f7425ee837f5acd9b3d` 的本机完整合同测试实际 **1358 项通过**：
`core:ai` 178、`core:domain` 71、`remote-text` 1109，失败/错误/跳过均为 0。
命令为 `python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test`，
随后 `python3 scripts/verify-ai-protocol-results.py` 对实际方法清单、XML 新鲜度和完整源码输入严格通过。
输入 SHA256 `d19a71294449fcd7b3bbb64585958a8107b42787798d4e1ca773928adffd3dfa`。
其中 27 项新增方法覆盖 canonical WAV、冻结排空/CAS/UNKNOWN、恢复删除实际入口、actor 退出/打开退休竞态、合成资源释放、v9 往返/重放与旧格式严格边界、纯原音搜索与事实回顾。
沿用的旧图片包独立 Node/OpenSSL 字节夹具和旧完整备份也在本轮实际执行。

因果日志包含早期编译错误、只读 current() 回归、v9 委托/回执与 journal MIME 拒绝、退休前旧就绪观察的打开竞态、纯原音搜索/回顾旧图片假设。
对应修复及失败 XML 均保留；不把中间失败轮或缓存旧 XML 计作本次通过。先前 `f9f8203c807a8a179e78ffb8db1dc97f044a20c2` 的 1355 项和
`6d28e975d39b2342dba876fabf494ebcbce718c6` 的 1356 项仅是各自输入版本证据，不替代最终轮。
同一产品提交执行 `python3 scripts/jvm-gradle.py -p checks/reminder-native :test`，随后
`python3 scripts/verify-reminder-native-results.py` 严格通过：实际 **41 项**，失败/错误/跳过 0。
该构件包含实际整个 data 源码、Android 提醒 adapter/receiver/service/broker 和实际 Room KSP；仅未使用的 Activity/R 是受控壳。
原生构件输入 SHA256 `77fcfa25d16a0f47858c2743a362c4e46b3e744e310ea80986bbca1dd4dce8f1`，
生成的 Room v15 与已提交 schema 逐字节一致。它不包含新录音适配器，也不是 SQLCipher 原生运行或设备通知证据。

本轮成功与失败日志、最终 XML/输入清单及方法核验收据见
[本地验证证据 ZIP](evidence/D03_CONTROLLED_AUDIO_LOCAL_EVIDENCE.zip)：838884 字节，
SHA256 `374bab52e1ec09f16d52deed13ff3879869f779db31e10f9f403e1871258335e`，675 项文件清单逐项核验。
独审包的外层交付 SHA 单独记录；文档/证据封存不改上述产品测试输入。

本地 SDK 只读 gate 未满足 `ANDROID_HOME` 指向已接受许可 SDK 的条件，没有安装 SDK、接受条款或启动设备。
Android metadata/APK/lint、Compose/Preview、真实录音/播放/权限/后台/OEM/SQLCipher/16 KB/性能/可访问性 **NOT RUN**。
既有 D07 CI 与设备边界不借用为本片结论。实现候选须独审后才正常推送并触发必要 CI；本次不改 main，不强推、不部署。
