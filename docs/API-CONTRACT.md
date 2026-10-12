# Android 协议草案 v1（平台尚未实现）

根路径 `/api/terminal/v1`，仅 HTTPS，JSON envelope：`{"ok":true,"data":{...}}`。
业务失败返回 ok=false 和稳定 code；身份无效 HTTP 401/403。禁止将旧硬件 API 作为 fallback。

| POST 路径 | 请求 | data |
| --- | --- | --- |
| capabilities | protocolVersion | protocolVersion=1, productionReady；仅全部验收后 true |
| login | username,password,deviceId,sdkFingerprint,appVersion | token,bindingId；后端从账号及已审批设备归属解析景区 |
| config | 空对象 | messages（内部状态码到文案），后续补配置版本/阈值 |
| sync | cursor,limit<=25 | batchId,changes,nextCursor,caughtUp |
| sync/ack | batchId,cursor | 确认本批实际应用；应校验绑定和任务代次 |
| verify | eventId,deviceId,bindingId,capturedAt,localStatus,subjectId | eventId,status,allowed,recordStored |
| events | events<=100 | acknowledgedEventIds，仅持久化成功的逐条 ID |
| counts | from,to（YYYY-MM-DD） | people,countingLabel,updatedAt |

照片 GET `/api/terminal/v1/photos/{opaque-id}` 使用设备会话认证，只能读取绑定景区授权任务中的照片。禁止任意路径/跨景区读取。

UPSERT 变更字段：subjectId（不透明内部编号）、revision（单人单绑定单调递增）、operation、expiresAt（UTC 毫秒）、photoPath、photoSha256。DELETE 仅身份、revision、operation。同步不传完整证件号/手机号/姓名。

首次同步必须由稳定快照加增量游标支撑，删除优先，游标过期显式报错重建。计数、摘要和入库失败回执接口待补齐；不能仅 caughtUp=true 就承诺所有会员已正确导入。过期游标重建不得删除未回传记录。

## 平台必须实现的事务约束

1. 每次 verify 根据当前登录会话重新检查账号/设备状态、景区绑定，客户端传来的景区不作为权限依据。
2. 按景区+人员统一锁定，检查当前权益、拉黑、60秒、单点双向互斥、次数，并原子保存结果和通行记账。
3. (设备,eventId) 唯一；重复请求校验正文一致并返回原结果，另记重传计数。不二次扣次。
4. 接收拒绝/未匹配事件用于分析，不将 unknown subject 归到真实用户。
5. events 仅做历史事件接收/补充，不执行新的放行或扣次；若已有 verify 结果，保留权威结果。不能信任客户端的 allowed。
6. counts 只根据服务器会话返回所属景区全设备统计，无设备人员明细。
7. 账号登录继承平台密码校验、限流、锁定、会话撤销机制；不能为新端绕过原登录安全控制。
8. 业务事件发件箱与会员变更同事务落库；删除代次覆盖旧新增代次，断网设备显示待确认。
9. 允许结果与设备收到结果并不等同于真实入园；超时未知结果及人工补录需要核对，不能静默自动退次/重开。

## 后续必做

错误行单独回执及重试、不阻塞后续撤销；特征版本与设备绑定代次；同步断点对账；后台码表、授权码权限及审计；网页仅景区人数权限；隔离库并发测试。完成后方可开启 APP 的正式核验入口。
