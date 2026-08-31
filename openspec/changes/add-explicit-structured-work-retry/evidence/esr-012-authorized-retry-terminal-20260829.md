# REQ-ESR-012 授权重试终态证据（2026-08-29）

## 范围和结论

- 唯一任务：`e189e412-4a6a-403e-9e45-09f9d8c4bca6`。
- 唯一重试请求：`POST /api/tasks/{taskId}/retry`，HTTP 204；2026-08-29 17:19:11.3165491+08:00 发出，17:19:11.4763761+08:00 返回，耗时 156 ms。
- 一次性哨兵记录的发送次数为 1；未创建任务，未发送第二次 retry。
- 新 attempt：`fd73e5d6-b896-4285-a5f0-64183433d8a5`（attempt #7）。
- 终态：任务 `FAILED`，处理状态 `FAILED`，覆盖状态 `PENDING`，`canRetry=false`。
- 失败类型：Java `structured_output_invalid`；KEE 初次和唯一修复均为 `feature_relation_owner`，均为 `finish_reason=stop`，不是网络、超时或长度截断。
- 按授权边界，已停止业务执行，不再 retry。

## 创建前门禁

- 2026-08-29 17:18:39+08:00：20 个任务、0 个在途任务。
- 目标任务和核对 work 均为 `FAILED`，work 无 accepted hash、无租约。
- 页面：2 `COMPLETED`、6 `PLANNED`。
- 临时核对：200 条关系、590 条绑定。
- 正式核对、来源终态、测试点、测试用例、步骤、制品均为 0。
- 上游数据：503 条事实、12 条审查发现、294 个正式功能、972 条来源绑定；候选审计为 95/303/303；材料清单为 5 文档/470 单元。
- 创建前数据库摘要：`29B5D464FB3DC5AC9039CC77A214C6C5D2F7B173C2C0BD5CD4D618087616A43A`。

## 运行时间线

- 17:19:18+08:00：attempt #7 开始，租约心跳持续有效。
- 17:28:03+08:00：第一个本轮页面调用完成；页面变为 3 `COMPLETED`、5 `PLANNED`，临时核对增长到 300 条关系、980 条绑定。
- 17:28:05 至 17:49:43+08:00：下一页面完成初次调用和唯一修复；期间租约持续续期。
- 17:49:43+08:00：KEE 返回 HTTP 400；attempt #7 以 `structured_output_invalid` 失败。
- 17:49:47+08:00：任务回到明确失败终态，20 个任务、0 个在途任务。

## KEE 安全诊断

| 阶段 | UTC 时间 | 固定分类 | finish reason | content bytes |
|---|---|---|---|---:|
| initial | 2026-08-29T09:37:49.491059818Z | `feature_relation_owner` | `stop` | 51,143 |
| repair | 2026-08-29T09:49:43.610041574Z | `feature_relation_owner` | `stop` | 40,978 |

安全诊断仅包含固定分类、阶段、完成原因和字节数；没有读取或保存模型输入、模型输出、材料正文或凭据。

## 原子写入边界

- 本轮新增 1 个完成的临时页面：页面从 2/6 变为 3/5，临时关系从 200 增至 300，临时绑定从 590 增至 980。
- 正式核对、来源终态、测试点、测试用例、步骤和制品仍全部为 0。
- 上游事实、审查发现、正式功能、来源绑定、候选审计和 5 文档/470 单元材料清单未变化。
- 因此准确结论是：**保留了部分 staging 进度，但零正式业务发布、零下游生成、无制品**；不能表述为“数据库零写入”。
- 终态数据库摘要：`856BFBF86947918F107C778C72D086E0005649C4CB55EFC3BEFF059CD0A01F6E`。

## 运行物和证据

- Java JAR：`testcase-agent-backend-esr012-repeatable-read-current-read-20260829.jar`。
- Java SHA-256：`EDEE4223E80B4239E723657B59B2AD4C7973411381BE22C896FBC658A33B3F5B`。
- Java PID：20660；外层等待 3060 秒；8082 单实例。
- KEE image：`sha256:34ae7dfa3f695f0627eb0a38e6db8358ee237fadb1a18af9d5d964f27a85ff57`。
- 一次性请求及监控证据：`backend/target/acceptance/esr012-authorized-retry-20260829-1719/`。
- 终态全量数据库快照：`backend/target/acceptance/esr012-repeatable-read-current-read-deploy-20260829/snapshot-authorized-retry-final-failed.json`。
- KEE 固定安全诊断：`backend/target/acceptance/esr012-authorized-retry-20260829-1719/kee-safe-diagnostics.json`。

由于任务没有完成，companion 6.5/8.5 仍不满足；API/页面/Excel 最终同任务验收不能用本次失败结果冒充完成。
