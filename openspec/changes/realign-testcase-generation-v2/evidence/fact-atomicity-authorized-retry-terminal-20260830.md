# 夹具 A 事实原子性窄恢复正式 retry 终态证据（2026-08-30）

## 唯一调用

- task：`4cc88a6f-fe10-42eb-96ad-f1e229d84bca`
- 调用前：`PARTIAL / FAILED / UNABLE_TO_GENERATE`，`canRetry=true`，21 tasks、0 inflight。
- 调用前 32 表总摘要：`CDECD182D2F692AE5ED91EA202CA0AD54B961D2CC428F342C2ECC6EBCACB1BE8`。
- Java JAR SHA-256：`9EECB7C9D098C63C8A11CC1C638B968E4B74AE724B88F213B8781A8318ED4739`；唯一 8082 PID 为 `20008`。
- 一次性 POST 哨兵创建后，于 `2026-08-30T22:24:36.6846943+08:00` 发送唯一 `POST /api/tasks/{taskId}/retry`；HTTP 204，168 ms，无响应正文。
- 本轮未发送第二次 retry，未创建替代 A 或夹具 B，未手工修改数据库，未重启 KEE/MySQL。

## 恢复时间线

- POST 后顶层进入 `AUDITING / RUNNING / PENDING`，inflight 0→1。
- 两个旧 `functional-testcase-design` fallback work 先原子转为 `SUPERSEDED`；两个失败的事实 work 分别被重新排队并各新建 attempt #2，旧 attempt #1 保留。
- 第一个新 attempt：`ad516ca2-9b62-420a-ba88-ad5d9b0a1196`，work `7b2eacce-0752-46e9-a541-a514acabfe60`。
- 第二个新 attempt：`38c0b475-df2f-4cab-a201-ba56e5bac6bd`，work `e62088ad-f4cc-4e62-b788-7f4f0e294be0`。
- 两个新 attempt 均为 `business_validation_failed / FACT_DIRECT_EVIDENCE_UNSUPPORTED / $.requirement_facts[0].statement`；对应事实 work 均保持 accepted hash 为空，事实、引文和反馈均零接收。
- 两个事实窗口完成后，旧 fallback work 安全恢复为 `COMPLETED`；既有 2 test points、2 `unable_to_generate` outcomes、2 publications 的逐表哈希与 retry 前完全一致，没有重复生成正式用例或绑定。

## 最终 API / 数据库 / 制品

- 顶层终态：`PARTIAL / FAILED / UNABLE_TO_GENERATE`，21 tasks、0 inflight，`canRetry=false`，artifact present。
- work：2 FAILED requirement fact extraction；2 COMPLETED functional testcase design。
- attempt：2 COMPLETED；4 FAILED business validation（含两条保留历史 attempt 和本轮两条新 attempt）。
- V2 正式事实 0、事实引文 0、可测性反馈 0、反馈引文 0、测试点 2、正式用例 0、待确认用例 0、步骤 0、绑定 0；制品 1。
- 详情 API 不返回 failure summary，且对内部错误码、JSON path 和 V2 operation 枚举的安全扫描命中 0。
- artifact id：`076eddf7-a810-4a11-b365-3d900e6bd69f`；下载 HTTP 200，XLSX 5,551 bytes，SHA-256 `E60EE9082A06698BD89958224370BE63A3A4CDFCA316DB17B2A7CB75C94A57EF`。
- OfficeCLI 回读：恰好两张 Sheet `需求与功能清单审查发现`、`测试用例`；第一张 3 行（表头+2 条无法生成说明），第二张 1 行（仅表头、0 正式用例）。`validate` 通过，issues 0，五类公式错误、内部错误码、JSON path、operation 枚举、UUID、占位符命中均为 0。
- 内置 Browser 因本机管理策略校验暂不可用，未绕过安全控制；因此本轮没有声称完成实际 Browser DOM/控制台验收。页面同源与读者安全仅由 live API、Excel 回读和既有 57/57 前端门禁证明。

## 停止点

- 本轮 retry 已终态失败，按授权立即停止；不得再 retry。
- 新失败类型与原 `FACT_ATOMICITY_INVALID` 不同，说明连接词误拒已越过，但直接事实依据门禁仍拒绝两个窗口。需要先按 KEE/Java 职责边界完成证据驱动归因，不能据此放宽业务校验。
