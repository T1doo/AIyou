# 所选出处问答合同与本机协议假服务

本轮推进原 §5.2／§10.3 的问答代码缺口，不增加其他离线摘录功能。交付是多源问题、独立范围审阅、双协议完整响应及取消的可审合同，**真实 BYOK／正式远程问答仍未接入、未验证，不能标为两节问答完成**。

## 已有代码与实际缺口

| 层 | 本轮前状态 | 本轮结果 |
| --- | --- | --- |
| 双协议请求、HTTPS 根路径、SSE、有限错误处理 | 已有 ProtocolRequests／ProtocolStream／SseDecoder／OkHttpStreamingTransport | 复用，未开启网络 |
| 认证发送与回执 | newSourceBoundExchange 只从持久仓库读取一条当前获准原文；queue／admit／subscribe／commit 均为单源 | 保持单源边界；没有塞入额外片段或扩大 CallerText |
| 多源模型问题与回答 provider | LocalEvidencePacket 只支持本机检查；SyntheticEvidenceAnswerProvider 仅直接返回合成对象 | 新增有界 SelectedQuestionRequest／Reply 和纯 SelectedQuestionCodec，实际构造双协议请求并解析完整 SSE 后的严格 JSON |
| 正式 Activity 的问答控制流 | 默认没有真实回答端口，只接测试 Synthetic 与离线摘录 | 独立问答 service 参数默认 null；明确显示未接入。注入明确本机协议假服务时，走同一 Activity、范围确认、控制器与来源跳转 |
| 多源远程授权、连接及秘密 | 不存在多源原子持久发送准入／持续失效观察，也没有正式秘密解析与模型配置接线 | 仍缺，不能靠新增按钮、local selection confirmation、普通 exchange 或单源 ConsentGrant 代替 |

## 本次可执行的产品控制流

用户先在原检索中选择并确认出处，再主动打开“核对问题和所选范围”。该审阅冻结当前问题、目标协议／模型／版本、问题时刻／时区、选择身份及精确引用。只有同一个审阅对象能被确认一次；旧按钮不能消费后来新建的审阅。

本机假服务明确标注“仅内存验证，不连接外部模型”，确认只适用于这次本机验证，不是外发授权。默认 Activity 不安装假服务，原 LOCAL_ONLY 和无 INTERNET 包边界不变。假服务只接受 `.invalid` HTTPS 操作地址描述，不提供 Socket、OkHttp、秘密解析、HTTP 明文或 TLS 覆盖开关。注入是可信应用／测试代码，不是对恶意替换实现的安全沙箱。

每次执行先复用已确认来源 proof 并等 SQL COMMIT，再创建 exchange；创建与注册之间发生停止或失效时，返回的 exchange 也必须取消。取消在控制器锁之外执行。完整协议终态及严格输出校验之后，再检查一次 proof／COMMIT 和当前 request／view／session／service 归属，才可显示待核对回答。部分 JSON／delta 不展示，也不存档。

请求仅包含当前问题、时刻和选定片段的 sourceId／版本／Unicode 码点范围／精确 quote／记录日期／IANA 时区；原文其余部分、附件、秘密及未选记录不进入请求。固定系统规则与这些不可信数据分离。输入仍限制五源、1200 摘录码点／4800 UTF8 字节；协议请求另限制 32768 字节。输出限制五段、五个引用、1200 正文码点／4800 字节，SSE 另限制累计字节、帧、行、事件和文本。

严格 JSON 拒绝重复／未知／缺失字段、类型错误、尾随数据、越界整数、不完整 Unicode、深度或大小超限、noEvidence 与正文矛盾，以及任何未选 ID、旧版本、偏移或 quote 篡改。只有 Chat 完整 stop＋DONE 或 Anthropic 完整结束链才能进入结果解析。无证据是“当前选择不足”，不能推出某件事从未发生。

引用校验只能证明绑定有效，不能证明自然语言论断得到支持。结果始终标“生成内容，待核对”；不写事实、任务、长期记忆或缓存。原文跳转仍经 sourceFor、canOpen 和 SQL resolve。问题、范围、账号、路由、锁、service 身份、来源版本、删除与策略变化会使旧请求失效；不可合作的迟到返回也不能回填。

## 最小剩余接入工作

后续持久化授权代码片见 [F05_SELECTED_QUESTION_AUTHORITY.md](F05_SELECTED_QUESTION_AUTHORITY.md)：复用既有 grant/source jobs 实现整组单次授权、准入、持续失效观察和结果摘要回执，并以明确内存传输验证。该片不把本机范围确认升级为外发权限；以下真实 provider/配置/秘密与生产界面接线仍未完成。

真实远程 provider 需要在同一权威持久事务中冻结并核准全部来源的当前版本、处理策略、access generation、连接／模型／配置和逐源同用途 ConsentGrant，原子消费一次发送准入；执行期间持续观察撤回并取消自有请求，完成后再次核验全部来源及回执。还需正式连接配置与受保护秘密解析、用户可见目标与最小范围确认，以及受上述权威约束的真实传输适配器。

当前接口只有 LOCAL_PROTOCOL_FIXTURE 模式。启用真实 provider 不能仅替换 fixture、打开 INTERNET 或把多个单源授权拼在一起；它需要单独实现和审阅上述权威链路。本轮不新增 Room schema、权限、依赖、凭据、付费、真实模型请求或网络配置。

## 验证边界

主机定向用例编译实际 model／domain／ai／控制器，实际 SQL proof 使用 Python SQLite 连接，仅协议服务端是本机内存 fixture。Compose 用例走正式 AIyouApp 与真实 codec/provider，但检索端口和字节流为明确合成数据。它们不等于真实模型、Android Room／SQLCipher、设备重启／锁／SAF、真机无障碍与性能验收；这些仍未运行。
