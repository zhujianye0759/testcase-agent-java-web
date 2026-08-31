# 功能候选协议 V1 固定样例

本目录冻结 KEE 与 Java Web 共同遵守的协议 V1 字面量。目录中的项目名、单元和业务文字均为合成的接口测试数据，不是梅州真实任务的解析结果，也不能作为真实验收证据。

文件用途：

- `request.json`：完整隔离 Skill 请求，包含 4 个连续目标单元和 1 个同材料上下文单元。
- `model-response.json`：KEE 内部模型的嵌套中间对象；不得直接返回给 Java。
- `canonical-success.json`：严格的公开成功信封，包含多证据候选、非功能结论和待确认候选。
- `model-response-omitted-unit.json` / `canonical-omitted-unit.json`：模型遗漏一个目标单元时，KEE 只补充 `unresolved/model_omitted_unit`。
- `model-response-unusable-sibling.json` / `canonical-unusable-sibling.json`：一个坏候选不删除同窗口的有效同级候选。
- `stable-failures.json`：Java 可稳定分类的公开失败信封。
- `hash-vectors.json`：四字节大端长度前缀 SHA-256 的字段序列、规范化文字和字面量摘要。

测试必须直接断言 `hash-vectors.json` 中的摘要，不能用被测生产实现重新生成预期值。任何字段名、顺序语义、状态/原因组合、文字规范化或摘要变化都属于协议变更，必须同时修改两个仓库并重新审查。
