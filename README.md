# AIyou

爱忆：Android 原生、中文优先、本地优先的私人记忆工具。

原始设计规范保留于 `docs/AIyou_Master_Plan.md` 和
`docs/AIyou_Product_Architecture.docx`。初始提交只有上述方案及 README、
`.gitattributes`；DOCX 提及的 Starter ZIP 未提供。本轮 Android 工程、工作区
规则、构建说明与 CI 是新建文件，未声称导入该压缩包。

当前范围：D00与D01之后，D02已有加密文字、草稿恢复、不可变原文版本及本地附件文件协议；
本轮接通已存原文版本的单张静态 PNG 导入、确认与查看。完整D02仍待原生设备门槛，
已提供本机原文字面查找、捕获日期与手工标签组合筛选；完整中文索引／D06 仍未完成。混合输入、录音、模型和系统提醒尚未实现。
手工正式待办已支持明确创建、加密保存、查看、完成和取消；所有任务未设时间、没有系统提醒，完整 D07 仍未完成。
切片范围与证据边界见 [手工待办说明](docs/MANUAL_TASKS_SLICE.md)。默认 `LOCAL_ONLY`，没有
联网权限、真实模型调用、服务端或已授权角色素材。
用户主动选择当前原文与手工标签可导出 Markdown/JSONL，须确认明文风险并选择系统文件目的地；
附件、历史、待办与密钥不在此范围，完整加密备份与恢复仍未实现。
范围、部分文件处理和原生 NOT RUN 边界见 [开放格式导出说明](docs/MANUAL_EXPORT_SLICE.md)。
原文现可明确移入可恢复回收站，保留版本、标签、附件和加密编辑草稿；移入后排除当前
列表、检索、新导出与 AI，独立手工待办保持原状态。恢复后不参与 AI，旧远端授权不恢复。
无永久删除、自动清空，已导出文件无法撤回；完整 D08 仍未完成。
范围与实际验收边界见 [可恢复回收站说明](docs/RECYCLE_BIN_SLICE.md)。

- 构建与 SDK 许可：[docs/BUILD.md](docs/BUILD.md)
- 实际执行记录与阻塞：[docs/D00_STATUS.md](docs/D00_STATUS.md)
- D01设计范围：[docs/D01_DESIGN.md](docs/D01_DESIGN.md)
- D01实际测试及验收边界：[docs/D01_STATUS.md](docs/D01_STATUS.md)
- D01已确认像素缺陷修复及完整长文本补证：[docs/D01_PIXEL_FIXES.md](docs/D01_PIXEL_FIXES.md)
- D02 附件界面的范围与检查合同：[docs/D02_ATTACHMENT_UI.md](docs/D02_ATTACHMENT_UI.md)

