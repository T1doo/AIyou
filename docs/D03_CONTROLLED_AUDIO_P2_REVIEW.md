# D03 受控原音：独审四项 P2 修订候选

接续未获实现 ACK 的 `94905dfa0fb346864d2cd5602221d8adf15e6099`。本片不推送、不启动 CI，不新增权限、录音/播放 adapter、SDK、凭据或模型调用。

1. 编码在 monitor 外进行。编码返回后，接纳/清零波形与转换 FINALIZING 在同一个 cancel monitor 仲裁。
   编码期间已接受取消则丢弃编码结果、不写 intent/文件；FINALIZING 之后的取消不能撤销此前已准入的保存。
   内部构造器使用默认 canonical WAV 编码函数的注入端口，只为可确定的编码中并发回归，无用户入口或 SDK。
2. 日期分支仅递归生成声明的 legacyMetadata，再由最外层套 v9；不将 v9 字节标作 v2/3/4。
   回归覆盖纯原音+事件日期的 current、activities、history（含/不含活动）、人物+编辑草稿嵌套，以及选择包重放和完整备份往返。
   日期/收藏/回收站原有空正文来源门只扩展到实际 audio/wav 附件；hash、版本、generation、日期和删除恢复检查不降低。
3. 活跃 RAM 原音的对账不因密文不完整而推进 FAILED；保留同 intent、base+1 WRITING、冻结和波形，同次重试到 base+2 READY。
   新的 reservation 专用 abandonAudio 只接受原 base 或确切 WRITING/FAILED 后继，拒绝 READY/外部变更。
   用户明确放弃后先清理已注册文件，再以同 CAS 确认 FAILED，才释放波形；清理 ACK 未知保留波形，重试继续放弃。
   冷启动既有 recover 仍能标记丢失 RAM 的不完整附件 FAILED，不伪造原音进程死亡恢复。
4. legacy changeText/save/retry/discard/cancel/restore 的 UI 决策和接收均由同一门准入。
   已接收的 Edit/Save/DiscardCapture/RestoreCapture/RecoverDraft 携带不可持久化 permit，实际 actor dispatch 前再校验，finally 或队列拒绝时释放。
   旧编辑/保存/清空/丢 ACK 后恢复可以按冻结前 prefix 排空；冻结后入口不改 UI、不堆裸命令、不靠仓库末端拒绝污染全局 failed。

定向已通过；准确产品 SHA、实际完整测试/XML/输入指纹和修订证据包将在完整回归结束后填入。
保留各次失败：取消竞态、日期夹具 generation、纯原音日期来源门、日期版本套错、真实 SQL 不完整写后隐式清零、legacy UI/permit，以及闭包 continue 编译修正。
上一候选1358/41项仅代表上一产品输入，不用作本修订的通过证据。Android metadata/APK/lint/Compose/Preview、真实录音/播放/权限及设备验收仍 NOT RUN。
