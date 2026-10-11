# AIyou

爱忆：Android 原生、中文优先、本地优先的私人记忆工具。

原始设计规范保留于 `docs/AIyou_Master_Plan.md` 和
`docs/AIyou_Product_Architecture.docx`。初始提交只有上述方案及 README、
`.gitattributes`；DOCX 提及的 Starter ZIP 未提供。本轮 Android 工程、工作区
规则、构建说明与 CI 是新建文件，未声称导入该压缩包。

当前范围：D00与D01之后，D02已有加密文字、草稿恢复、不可变原文版本及本地附件文件协议；
本轮接通已存原文版本的单张静态 PNG 导入、确认与查看。完整D02仍待原生设备门槛，
混合输入、录音、搜索、模型和提醒尚未实现。默认 `LOCAL_ONLY`，没有
联网权限、真实模型调用、服务端或已授权角色素材。

- 构建与 SDK 许可：[docs/BUILD.md](docs/BUILD.md)
- 实际执行记录与阻塞：[docs/D00_STATUS.md](docs/D00_STATUS.md)
- D01设计范围：[docs/D01_DESIGN.md](docs/D01_DESIGN.md)
- D01实际测试及验收边界：[docs/D01_STATUS.md](docs/D01_STATUS.md)
- D01已确认像素缺陷修复及完整长文本补证：[docs/D01_PIXEL_FIXES.md](docs/D01_PIXEL_FIXES.md)
- D02 附件界面的范围与检查合同：[docs/D02_ATTACHMENT_UI.md](docs/D02_ATTACHMENT_UI.md)

