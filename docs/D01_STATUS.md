# D01 状态

D00已经主对话验收；D01实现中，尚未宣称CI或视觉验收通过。D02未开始。

实现范围和兼容记录见[D01_DESIGN.md](D01_DESIGN.md)。原方案文件保持原样。

本地真实检查：

- 5项Python Token/对比度契约测试：PASS。
- core:domain:test --rerun-tasks：PASS，6任务执行，3测试0失败/错误/跳过。
- app:resolvableConfigurations：FAIL，Gradle ConcurrentModificationException；
  保留/workspace/d01-configurations.log，不将旧XML当本次测试证明。
- 本地SDK预检：FAIL，SDK不存在；未安装/接受新协议。
- 针对性依赖图解析与锁生成：PASS；直接resolve文件曾遇Android secondary
  variant歧义，改为组件图解析，不把此步骤当作Android artifact构建验证。
- 独审在96d48e5发现PNG结果guard可接受截断假文件；已修复完整结构/CRC/解压
  检查，新增4项反例回归，本地共9项Python测试PASS。
- 96d48e5 hosted CI run38020086747：3领域测试、5Token测试、SDK预检、两个
  AAR metadata及assembleDebug PASS；严格lint FAIL（ModifierParameter）。
  已修正MascotBadge/MainScreen可选参数顺序，未关闭检查；旧日志已保全。
- d46f1ea独审定点PASS，原P3关闭；hosted run38020397448重复测试/SDK/
  metadata/assemble PASS、designsystem lint无问题，app lint FAIL，10条
  D00占位字符串已无调用。已移除这些失效资源，未抑制UnusedResources。
  渲染步骤改为assemble成功后即执行并保全，即使lint失败仍保持job失败。
- 本地Android构建、lint、Preview渲染：NOT RUN，使用既有hosted CI路径验证。
- 真机、性能、TalkBack、真实键盘与200%字体交互：NOT RUN。

源码独审与当前SHA的CI结果待冻结后补充；不把Preview注解或编译当视觉通过。
