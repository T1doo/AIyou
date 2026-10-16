# AIyou

爱忆：Android 原生、中文优先、本地优先的私人记忆工具。

原始设计规范保留于 `docs/AIyou_Master_Plan.md` 和
`docs/AIyou_Product_Architecture.docx`。初始提交只有上述方案及 README、
`.gitattributes`；DOCX 提及的 Starter ZIP 未提供。本轮 Android 工程、工作区
规则、构建说明与 CI 是新建文件，未声称导入该压缩包。

当前范围：D00/D01 工程之后，已有加密文字、草稿恢复、不可变原文版本、PNG/JPEG
原图与混合文字/图片捕获；录音仍未实现。完整 D02/D03 仍待原生设备门槛。
已提供同一加密库中的持久化中文词法索引、捕获日期与手工标签组合筛选、原文核验与定位高亮、失效和有界重建；工程合同见 [LOCAL_PERSISTENT_INDEX_CONTRACT.md](docs/LOCAL_PERSISTENT_INDEX_CONTRACT.md)。完整 D06 与设备性能验收仍开放。
手工正式待办支持明确创建、加密保存、查看、完成、取消及明确日期/可选时刻、时区和
重复时刻选择；来源摘录可另存为待确认 DRAFT，只有用户明确确认才能成为正式待办。
系统提醒尚未实现，完整 D07 仍未完成。范围见 [待办时间合同](docs/MANUAL_TASK_DATES_CONTRACT.md)
与 [持久化待确认草稿](docs/F06_PERSISTENT_TASK_PROPOSALS_STATUS.md)。默认 `LOCAL_ONLY`，没有
联网权限、真实模型调用、服务端或已授权角色素材。
用户可主动导出当前原文与手工标签为 Markdown/JSONL，另可明确勾选全部正式待办与
已保存 DRAFT（各最多 256 条，也支持不选原文）；超限整体拒绝并提示单独导出原文，
不截断。已处理提案历史、未保存表单、提醒、密钥和联网授权不导出。图文 ZIP 可包含
选定原图；所有开放格式均须确认明文风险并自行选择系统文件目的地。
独立口令加密记录包已支持所选原文/原图及可选正式待办/DRAFT 的有限新副本恢复，
恢复不重连模型、不授予远端权限，也不执行草稿；这仍不是全库备份或完整迁移。
范围与残件处理见 [活动开放导出](docs/F08_OPEN_ACTIVITY_EXPORT_STATUS.md)、
[图文 ZIP](docs/F08_OPEN_MEDIA_EXPORT_STATUS.md) 和 [加密活动迁移](docs/F08_TASK_PROPOSAL_PACKAGE_STATUS.md)。
真机 SAF、全新安装恢复到原文/附件、重连密钥和提醒原生演练仍为 **NOT RUN**。
旧 94/96 SQLite 初始化/退出超时根因仍未知；主机测试、Compose JVM、编译和 CI
均不能替代这些设备验收。当前补充及证据来源见 [F08 最小闭合](docs/F08_EXPORT_CLOSURE_STATUS.md)。
原文现可明确移入可恢复回收站，保留版本、标签、附件和加密编辑草稿；移入后排除当前
列表、检索、新导出与 AI，独立手工待办保持原状态。恢复后不参与 AI，旧远端授权不恢复。
单条永久删除候选已接通明确确认、持久删除意图、真实文件锁排空、原文历史及派生内容清理、同意图续删；
同一加密包的旧恢复回执不会重新创建已删成员。自动清空、介质取证擦除及外部文件撤回不在范围，
完整 D08 与原生设备验收仍未完成。实际范围、预算及失败边界见
[永久删除工程合同](docs/PERMANENT_ERASURE_CONTRACT.md)。
范围与实际验收边界见 [可恢复回收站说明](docs/RECYCLE_BIN_SLICE.md)。

- 构建与 SDK 许可：[docs/BUILD.md](docs/BUILD.md)
- 实际执行记录与阻塞：[docs/D00_STATUS.md](docs/D00_STATUS.md)
- D01设计范围：[docs/D01_DESIGN.md](docs/D01_DESIGN.md)
- D01实际测试及验收边界：[docs/D01_STATUS.md](docs/D01_STATUS.md)
- D01已确认像素缺陷修复及完整长文本补证：[docs/D01_PIXEL_FIXES.md](docs/D01_PIXEL_FIXES.md)
- D02 附件界面的范围与检查合同：[docs/D02_ATTACHMENT_UI.md](docs/D02_ATTACHMENT_UI.md)

