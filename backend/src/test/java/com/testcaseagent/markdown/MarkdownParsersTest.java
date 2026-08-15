package com.testcaseagent.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** [Req-ID]: REQ-KAG-004, REQ-KAG-007, REQ-ANA-001, REQ-ANA-002, REQ-ANA-003, REQ-ANA-005, REQ-EXP-007, REQ-CAG-006 */
class MarkdownParsersTest {

    @Test
    void parsesTheApprovedGenerationTables() {
        String markdown = """
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                | 1 | 登录 | 边界遗漏 | 《需求规格说明书》3.1：未说明锁定策略。 |

                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 正确凭据登录 | 用户\\|登录 | 已注册用户 | 输入账号<br/>输入密码 | 进入首页<br />显示姓名 | 需求 3.1 |
                """;

        MarkdownGenerationResult result = new MarkdownGenerationResultParser().parse(markdown);

        assertEquals(markdown, result.rawMarkdown());
        assertEquals(List.of(new MarkdownAuditRow(1, "登录", "边界遗漏", "《需求规格说明书》3.1：未说明锁定策略。")), result.auditRows());
        assertEquals(List.of(new MarkdownTestCaseRow("正确凭据登录", "用户|登录", "已注册用户", "输入账号\n输入密码",
                "进入首页\n显示姓名", "需求 3.1")), result.testCaseRows());
        assertThrows(UnsupportedOperationException.class, () -> result.testCaseRows().clear());
    }

    @Test
    /** [Req-ID]: REQ-CAG-006 */
    void rejectsTrailingProseAfterTheFinalGenerationTable() {
        MarkdownGenerationResultParser parser = new MarkdownGenerationResultParser();

        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现

                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                | 1 | 月度例会功能优化-查看 | 描述质量问题 | 原文列出了展示内容但未定义刷新时机 |

                ## 测试用例

                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 月度例会功能优化-查看_正向 | 信息中心 | 已登录系统 | 进入月度例会页面 | 展示月度例会内容 | 需求原文 4.2 |

                > 说明：未明确的内容已记录在审查发现中。
                """));
    }

    /** [Req-ID]: REQ-CAG-006 */
    @Test
    void rejectsEmbeddedJsonRawHtmlAndAThirdGenerationTable() {
        MarkdownGenerationResultParser parser = new MarkdownGenerationResultParser();

        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 登录 | 用户 | 无 | 输入账号 | 登录成功 | 需求 1 {"source":"model"} |
                """));
        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 登录 | 用户 | 无 | <span>输入账号</span> | 登录成功 | 需求 1 |
                """));
        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 登录 | 用户 | 无 | 输入账号 | 登录成功 | 需求 1 |
                | 补充用例 | 补充模块 | 补充前提 | 补充步骤 | 补充结果 | 补充需求 |
                | --- | --- | --- | --- | --- |
                """));
    }

    @Test
    void acceptsAnEmptyFindingsTableButRejectsUnsafeOrIncompleteGenerationPayloads() {
        MarkdownGenerationResultParser parser = new MarkdownGenerationResultParser();
        assertEquals(0, parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 登录 | 用户 | 无 | 输入账号 | 登录成功 | 需求 1 |
                """).auditRows().size());

        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                """));
        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 登录 | 用户 | 无 | 输入账号 |  | 需求 1 |
                """));
        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 登录 | 用户 | 无 | 输入账号 | 登录成功 | ![截图](x.png) |
                """));
        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ```json
                {}
                ```
                """));
        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 登录 | 用户 | 无 | 输入账号 | 登录成功 | 需求 1 |

                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                """));
        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                | 1 | 登录 | 描述不足 | 未说明锁定策略 |
                说明文字仍紧邻审查表格，不能作为独立尾注。
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 登录 | 用户 | 无 | 输入账号 | 登录成功 | 需求 1 |
                """));
        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 登录 | 用户 | 无 | 输入账号 | 登录成功 | 需求 1 |

                ## 额外说明
                """));
        assertThrows(MarkdownContractException.class, () -> parser.parse("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 登录 | 用户 | 无 | 输入账号 | 登录成功 | 需求 1 |

                {"unexpected":"json"}
                """));
    }
}
