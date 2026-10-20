# D03 受控原音：独审四项 P2 修订候选

后续父侧复审发现放弃后的 reconcile 仍可转 READY；本轮1369/41项不覆盖该遗漏。
残留 P2 的最小修订及新精确产品定向结果见[放弃后核对分流](D03_ABANDON_RECONCILE_REVIEW.md)，实现 ACK 仍待确认。

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

修订产品 SHA：`5f8a1956362781bfd6059477d621cfd942c95108`。

- 定向实际 71 项通过，包括四个原音/legacy 类和既有日期、收藏、回收站合同；这是提交前相同产品输入的局部证据。
- 同一精确产品 SHA 完整合同实际 **1369 项**：AI178/domain71/remote-text1120，失败/错误/跳过均为0。
  执行 `python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test`，随后严格方法清单/XML新鲜度核验通过。
  输入 SHA256 `e9fc902d98582132fcd7022026f0ebe99b45d08dc195feabf9fde7c5c87fe54d`。
- 同一产品 SHA 受控原生构件实际 **41 项**，失败/错误/跳过0，源码同步及真实 Room KSP 导出与原 v15 schema 逐字节一致。
  执行 `python3 scripts/jvm-gradle.py -p checks/reminder-native :test`，随后严格方法清单/源码/Room核验通过。
  输入 SHA256 `cd89d52cf98065f380df8274601f64a06f0f8b8179c501b521bb251b0c2984bb`。
- 相对未ACK首候选新增11项方法，累计38项原音/legacy专项方法。完整回归包含已有独立旧格式夹具，不为新格式放宽旧解析。

[修订本地证据 ZIP](evidence/D03_CONTROLLED_AUDIO_P2_LOCAL_EVIDENCE.zip)：1086399字节，866项文件清单，
SHA256 `08a6fb0a74ca3402d477f9285bc6efb205f25c29bafca47a7ab929f0ac1d2341`，ZIP CRC及逐项大小/hash已核。
原首轮证据包保留不覆盖，修订内层同时保存全部因果轮日志/失败XML与本轮最终XML/输入/方法清单。
保留各次失败：取消竞态、日期夹具 generation、纯原音日期来源门、日期版本套错、真实 SQL 不完整写后隐式清零、legacy UI/permit，以及闭包 continue 编译修正。
日期版本三项在合法来源之后实际观察到 RecordPackageInvalid；旧legacy三项观察到冻结后UI变化和已接保存未执行；不把夹具或编译失败当作产品根因证据。
上一候选1358/41项仅代表上一产品输入，不用作本修订的通过证据。
本机合同使用实际VM/controller与JVM平台壳、合成SQLite；原生41项编译实际data及Android提醒构件，但Activity/R仍为受控壳。
Android metadata/APK/lint/Compose/Preview、真实录音/播放/权限/通知/SQLCipher原生及设备验收仍 NOT RUN；D03/D04/D07整体验收不称完成。
产品提交之后的封存仅改文档/证据，交付完整SHA另列；本轮未推送、未启动CI，仍待父侧实现独审ACK。
