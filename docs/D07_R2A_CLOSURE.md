# D07 R2a 封存收据

生产实现及两项drain修正：`4d28c6f32ee9822a865be1ab2e9d552292576e8a`。
父级于2026-10-20独立只读复审该提交，给予两项修正限定ACK，无新增确定P1/P2；
此前R2a主体及持久合同的评审边界仍有效，不扩展成R2b或完整D07设备验收。

[唯一CI 39400001492](https://github.com/T1doo/AIyou/actions/runs/39400001492) 精确head SHA如上，
attempt=1、completed/success。必需步骤1–17、19–38及post步骤均success，18为既有条件skip。
已核非空测试、Room导出、完整JVM／ViewModel／Compose模拟、AAR、APK、无INTERNET合并权限、
仪器APK编译及DEX清单、许可／ZIP对齐、lint、Preview和报告上传的实际步骤结论。
仪器APK仅编译，未在设备运行；CI通过不证明OS/OEM提醒、锁屏、通知可见或送达。

本地主机精确输入及实际XML见[两项修正证据](D07_R2A_DRAIN_REVIEW_FIX.md)：
1303项唯一方法(AI178／领域71／remote-text1054)、失败／错误／跳过0；脚本168、token和HTTP依赖检查通过。
主机在旧HEAD加修复工作树上执行，源码指纹绑定实际字节；本CI另在最终4d28c6f执行，二者不混称。

## 产物元数据

共16项未过期产物，均绑定上述head；以下为5项必需产物。
表中大小及SHA256属于GitHub artifact ZIP，**不是APK文件字节摘要**。
没有重试此前被拒的APK／原始日志下载，没有读取二进制内容来冒充校验。

| 名称 | ID | ZIP字节数 | ZIP SHA256 |
| --- | --- | ---: | --- |
| aiyou-d01-debug | 12256001655 | 19308812 | 86d8b571e2af516e3d0850ddc27b3e0ff7ba132b6d68820bfbd9c5b0b9ad8a1f |
| d01-reports | 12255926800 | 94503809 | 77b7b0e043d417477381188e3c3d73222bf22309814e7ee3ef26839999b7c729 |
| d02-storage-test-apk | 12255706794 | 7637304 | 01d0543e698c699071f211e1bec42eba245832b05e936f80cfc39fbaa6bf9c43 |
| d02-production-test-apk | 12256616412 | 411129 | 4288fbe168c791882acc2e0cb956ab7552ab39a34dc3f8cf6e5762ea5c914d89 |
| d01-preview-render-smoke | 12255836784 | 89339656 | 6b786ca3684f2cb54b9bccf32d36e39f3ccca821e4183b3e9c6df1792b62a41e |

CI内APK路径：`app/build/outputs/apk/debug/app-debug.apk`；本地未下载APK。
[原始run／jobs／16项artifact元数据与清单](evidence/d07-r2a-ci39400001492-closure.zip)：
4941字节，SHA256 `410fe918b51b16e832474b953daae14d4955e1e8b933d3631b01d90c7576573b`。
CLI gh的401路径未重试；本次只读核验通过现有授权GitHub连接成功完成。

R2a封存后仅进入R2b设计。Android receiver／JobService、cold owner、前台同门接线、权限UI与重建epoch
仍未实现；生产仍使用断开驱动。LOCAL_ONLY、无自建后台、无模型真实调用及两份原方案保持不变。
