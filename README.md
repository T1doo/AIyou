# AIyou

爱忆：Android 原生、中文优先、本地优先的私人记忆工具。

原始设计规范保留于 `docs/AIyou_Master_Plan.md` 和
`docs/AIyou_Product_Architecture.docx`。初始提交只有上述方案及 README、
`.gitattributes`；DOCX 提及的 Starter ZIP 未提供。本轮 Android 工程、工作区
规则、构建说明与 CI 是新建文件，未声称导入该压缩包。

当前范围：D00 原生 Kotlin / Compose 最小工程，包含“此刻、回忆、待办”和
设置占位入口；记录、搜索、模型、提醒尚未实现。默认 `LOCAL_ONLY`，没有
联网权限、真实模型调用、服务端或已授权角色素材。

- 构建与 SDK 许可：[docs/BUILD.md](docs/BUILD.md)
- 实际执行记录与阻塞：[docs/D00_STATUS.md](docs/D00_STATUS.md)
- D00 冻结后须独立审查；通过后才进入 D01。

