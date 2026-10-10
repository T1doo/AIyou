# D01 原图小包交付

原最终预览 artifact `11658682155` 为 36,224,524 字节，报告包为
37,080,342 字节；都超过聊天附件 20 MB 上限。当时没有单独的图片小包。
这次只改变后续 CI 的交付结构，产品源码、工具链、严格 lint 和视觉验收状态不变。

在实际 Preview 生成、验证和 198 项 PNG 完整性检查成功后，同一 runner 运行
`python3 scripts/package-visual-previews.py`，按原始 Render 方法分五个普通 ZIP：

| 独立 artifact | 原图数量 | 覆盖 |
| --- | ---: | --- |
| d01-visual-now | 28 | 此刻七状态 × 四尺寸 |
| d01-visual-memories | 28 | 回忆七状态 × 四尺寸 |
| d01-visual-tasks | 28 | 待办七状态 × 四尺寸 |
| d01-visual-components | 112 | 八组件 × 七状态 × 两尺寸 |
| d01-visual-shell | 2 | 主导航两尺寸 |

七状态为正常、禁用、加载、错误、长文本、空、离线。页面尺寸为 360dp、
320dp/fontScale2、800dp横屏、840dp大屏；组件与主导航含 360dp 和
320dp/fontScale2。全部 198 张原图都保留；不调整尺寸、不重编码、不降质。
这不是额外生成图片，也不是视觉验收。

每个内层 ZIP 硬上限 17,000,000 字节，超限时失败并且不上传。上传采用
compression-level 0，仅逐包上传，为平台外层 ZIP 留出距离 18 MB 的余量；
交付时还需核验 GitHub 实际 artifact 大小，每个下载包必须小于 18,000,000 字节。
原完整 artifact 与报告保留。包装失败不隐藏 lint、测试或构建失败。

各包含 README.txt、MANIFEST.json 和原路径 pngs/。清单记录每张图片的原始文件名、
像素尺寸、原始字节数和 SHA-256；PNG 必须与先前完整性检查生成的 manifest 摘要一致。
`d01-visual-index` 单独提供五包尺寸、摘要和全部图片清单；同一 JSON 也写入 CI 日志。

用户从正常 GitHub artifact 链接下载小包，再上传主对话。若下载到的外层 ZIP 内还有
同名 ZIP，可以解开外层后上传内层。没有更换网络路由或尝试绕过此前代理隧道 403。
自动化审查环境仍未获得可读取的实际 UI PNG，真实像素检查为 BLOCKED / NOT RUN；
主对话取得图片后，才可独立检查截断、重叠、可读性和内容分区。

包装回归测试使用明确标记的合成字节，不是 UI 图片或渲染/视觉证据。
最终精确大小、SHA、run/artifact 链接由实际 CI 日志和 GitHub 元数据交付，
不以本地包装单测冒充真实预览图交付。D02 未开始。
