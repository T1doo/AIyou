# D03 放弃后核对分流：残留 P2 修订候选

前置交付 `538dd9558b5b485c8c6bff6c8f978a6d9ce25e4a` 的四项 P2 主体已由父侧复审闭合，但仍未获得实现 ACK。
本次仅修复其指出的 reconcile 残留，不推送、不启动 CI，不新增权限、adapter、SDK 或真实模型调用。

产品 SHA `3fcd55c3cea788558a42e90c66fa9dc34ca338a6`：生产只增加 actor 执行入口的两行分流。
reconcile 在实际执行时读取 abandonRequested；已接受放弃则调用同一 abandon 路径并返回，不进入 reconcileAudio 保存恢复。
读取发生在 actor 内，所以先前已入队但尚未执行的放弃也按队列顺序生效。retry、强制收尾的既有放弃分流不改。
存储层仍只接受确切 base/本次 WRITING/FAILED 后继；READY 或外部字段变化保持拒绝，不删除文件、不伪造完成或解冻。

新增真实 SQL/加密存储回归：capture-published 后抛错，留下 WRITING 和已认证完整密文；明确放弃在清理前的文件锁入口失败，
两次核对期间保持 UNKNOWN/冻结、同一版本/intent、完整文件和非零 RAM 波形；解除故障后核对只能继续清理本 intent，
确认 base+2 FAILED、文件已删除后才解除冻结并清零波形。旧实现实际得到 READY，已保留失败 XML。
现有 SQL 回归补充拒绝放弃 READY 和外部变更后，确切快照/已认证原音字节仍完整的断言。

实际执行 `python3 scripts/jvm-gradle.py -p checks/ai-protocol :remote-text:test --tests '*ControlledCaptureAudioTest' --tests '*CaptureAudioSqlTest' --tests '*LegacyCaptureGateTest'`。
精确产品提交 **30 项通过**：Controller17、SQL10、legacy3，失败/错误/跳过0。
选定三类的源码方法集与实际 XML 逐项一致，XML 晚于真实执行输入捕获，完整源码指纹重新核验一致：
`1bbc1e215799a9c8a308105da1a445fbad259711c7e5acff22ddbfae89791d7e`。
本次是明确的定向验证，没有执行全量方法核验脚本，不把它称为完整套件通过。

[本次因果与定向证据](evidence/D03_ABANDON_RECONCILE_EVIDENCE.zip)：9359字节，9项文件清单，
SHA256 `21b24307be032e9eb74b0222c9f0268dab1aca456c6e10e1fbefa7cb9c7c78f6`，CRC/逐项大小与hash已核验。
之前的1369/41项仅对应 `5f8a1956362781bfd6059477d621cfd942c95108`，本次未重跑，不能充作本次产品结果。
原两份方案、core生产代码、Manifest、依赖和Room schema未改；本次 Android APK/lint/Compose、原生构件与设备验收均 NOT RUN。
仍待修订实现独审 ACK，D03/ASR/OCR/D04/D07 整体验收均不称完成。
