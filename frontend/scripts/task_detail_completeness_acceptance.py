"""Deterministic browser acceptance for the task-detail completeness workflow.

[Req-ID]: REQ-CWR-001, REQ-CWR-002

Run against a locally served frontend, for example:
  $env:TESTCASE_WEB_URL = 'http://127.0.0.1:5175'
  python frontend/scripts/task_detail_completeness_acceptance.py
"""

import json
import os
from copy import deepcopy
from pathlib import Path
from typing import Any

from playwright.sync_api import Page, Route, sync_playwright


BASE_URL = os.environ.get("TESTCASE_WEB_URL", "http://127.0.0.1:5175").rstrip("/")
RUN_ROOT = Path(__file__).resolve().parents[2] / ".comet" / "runs" / "all-completeness-task-detail"

BASE_PROGRESS = {
    "currentBusinessStage": "双向审查",
    "materialDocumentTotal": 4,
    "completeMaterialDocumentCount": 4,
    "materialUnitTotal": 30,
    "processedMaterialUnitCount": 30,
    "totalAuditWork": 12,
    "completedAuditWork": 12,
    "failedAuditWork": 0,
    "featureCandidateTotal": 8,
    "functionListMissingCount": 2,
    "requirementMissingCount": 1,
    "conflictCount": 1,
    "splitCount": 1,
    "mergeCount": 1,
    "insufficientEvidenceCount": 1,
    "frozenComplete": True,
    "frozenFeatureTotal": 8,
    "generationEligibleFrozenFeatureCount": 7,
    "generationIneligibleFrozenFeatureCount": 1,
    "expectedTestCaseTotal": 28,
    "acceptedTestCaseCount": 28,
    "coverageStatus": "审查与冻结完整",
    "businessReason": "材料与审查工作已完成，结果可按业务阶段追溯。",
}


def detail(status: str, **overrides: Any) -> dict[str, Any]:
    """Build a business-safe task-detail fixture while retaining internal-field decoys."""
    result: dict[str, Any] = {
        "id": "task-internal-id-778",
        "taskMode": "ALL",
        "status": status,
        "totalBatches": 7,
        "completedBatches": 7,
        "artifactReady": status in {"COMPLETED", "PARTIAL"},
        "artifactId": "artifact-internal-id-991" if status in {"COMPLETED", "PARTIAL"} else None,
        "frozenScope": {
            "state": "FROZEN",
            "materialCategory": "admission_material",
            "admissionType": "requirements_spec,function_list",
            "documentCount": 4,
            "knowledgeEngineDocumentId": "kee-document-should-not-render",
        },
        "batches": [{"featureId": "feature-internal-001", "status": "ACCEPTED"}],
        "auditRows": [{
            "sequence": 1,
            "subjectOrFeature": "用户登录",
            "issueCategory": "需求描述待澄清",
            "evidenceComparison": "登录失败后的提示方式尚未明确。",
            "evidenceCursor": "cursor-should-not-render",
        }],
        "testCaseRows": [{
            "caseName": "登录失败提示",
            "featureModule": "用户登录",
            "preconditions": "已创建有效账号",
            "executionSteps": "输入错误口令并提交",
            "expectedResult": "提示登录失败原因",
            "requirementContent": "连续失败时给出明确提示",
            "rawModelTrace": "trace-should-not-render",
        }],
        "businessProgress": deepcopy(BASE_PROGRESS),
        "knowledgeEngineCursor": "kee-cursor-should-not-render",
    }
    result.update(overrides)
    return result


def fulfill_json(route: Route, payload: Any, status: int = 200) -> None:
    route.fulfill(status=status, content_type="application/json", body=json.dumps(payload))


def assert_no_horizontal_overflow(page: Page) -> None:
    dimensions = page.evaluate("""() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    })""")
    assert dimensions["scrollWidth"] <= dimensions["clientWidth"], dimensions


def assert_visible_focus(page: Page, selector: str) -> None:
    page.locator(selector).focus()
    focus = page.evaluate("""selector => {
      const element = document.querySelector(selector)
      const style = element ? getComputedStyle(element) : undefined
      return {
        focused: document.activeElement === element,
        outlineStyle: style?.outlineStyle,
        outlineWidth: style?.outlineWidth,
      }
    }""", selector)
    assert focus["focused"], focus
    assert focus["outlineStyle"] != "none" and focus["outlineWidth"] != "0px", focus


def screenshot(page: Page, name: str) -> None:
    page.screenshot(path=str(RUN_ROOT / name), full_page=True)


def wait_ready(page: Page) -> None:
    page.locator('[data-state="ready"] .task-detail__summary').wait_for(state="visible")


def main() -> None:
    RUN_ROOT.mkdir(parents=True, exist_ok=True)
    counters = {"error": 0, "cancel": 0, "retry": 0}
    state = {"cancelled": False, "retried": False}
    console_errors: list[str] = []

    def handle_api(route: Route) -> None:
        request = route.request
        path = request.url.split("?")[0]
        method = request.method

        if method == "GET" and path.endswith("/api/tasks/task-loading"):
            fulfill_json(route, detail("COMPLETED"))
            return
        if method == "GET" and path.endswith("/api/tasks/task-error"):
            counters["error"] += 1
            if counters["error"] == 1:
                fulfill_json(route, {"message": "temporary"}, 503)
            else:
                fulfill_json(route, detail("COMPLETED"))
            return
        if method == "GET" and path.endswith("/api/tasks/task-empty"):
            fulfill_json(route, None)
            return
        if method == "GET" and path.endswith("/api/tasks/task-auditing"):
            progress = deepcopy(BASE_PROGRESS)
            progress.update({
                "currentBusinessStage": "双向审查",
                "completedAuditWork": 6,
                "frozenComplete": False,
                "frozenFeatureTotal": None,
                "generationEligibleFrozenFeatureCount": None,
                "generationIneligibleFrozenFeatureCount": None,
                "expectedTestCaseTotal": None,
                "acceptedTestCaseCount": 0,
                "businessReason": "审查仍在进行，尚未形成可生成的冻结功能范围。",
            })
            fulfill_json(route, detail("AUDITING", totalBatches=0, completedBatches=0, artifactReady=False,
                                      artifactId=None, businessProgress=progress))
            return
        if method == "GET" and path.endswith("/api/tasks/task-cancel-recovery"):
            fulfill_json(route, detail("CANCELLED" if state["cancelled"] else "AUDITING",
                                      artifactReady=False, artifactId=None,
                                      failureSummary="任务已取消" if state["cancelled"] else None))
            return
        if method == "POST" and path.endswith("/api/tasks/task-cancel-recovery/cancel"):
            counters["cancel"] += 1
            if counters["cancel"] == 1:
                fulfill_json(route, {"message": "cancel unavailable"}, 503)
            else:
                state["cancelled"] = True
                route.fulfill(status=204)
            return
        if method == "GET" and path.endswith("/api/tasks/task-partial-recovery"):
            if state["retried"]:
                fulfill_json(route, detail("COMPLETED"))
            else:
                failed_batch = {"featureId": "feature-internal-001", "status": "FAILED", "failureSummary": "生成服务暂不可用"}
                fulfill_json(route, detail("PARTIAL", completedBatches=6, artifactReady=True,
                                          batches=[failed_batch], failureSummary=None))
            return
        if method == "POST" and path.endswith("/api/tasks/task-partial-recovery/retry"):
            counters["retry"] += 1
            if counters["retry"] == 1:
                fulfill_json(route, {"message": "retry unavailable"}, 503)
            else:
                state["retried"] = True
                route.fulfill(status=204)
            return
        if method == "GET" and path.endswith("/api/tasks/task-discovery-failed"):
            fulfill_json(route, detail("FAILED", totalBatches=0, completedBatches=0, artifactReady=False,
                                      artifactId=None, batches=[], failureSummary="材料暂时无法识别"))
            return
        if method == "GET" and path.endswith("/api/tasks/task-stale-first"):
            fulfill_json(route, detail("COMPLETED", testCaseRows=[{
                "caseName": "过期的旧任务结果", "featureModule": "旧模块", "preconditions": "-",
                "executionSteps": "-", "expectedResult": "-", "requirementContent": "-",
            }]))
            return
        if method == "GET" and path.endswith("/api/tasks/task-stale-latest"):
            fulfill_json(route, detail("COMPLETED", testCaseRows=[{
                "caseName": "最新任务结果", "featureModule": "用户登录", "preconditions": "-",
                "executionSteps": "-", "expectedResult": "-", "requirementContent": "-",
            }]))
            return
        if method == "GET" and path.endswith("/api/tasks/task-completed"):
            fulfill_json(route, detail("COMPLETED"))
            return
        route.continue_()

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 820})
        page.add_init_script("""(() => {
          const nativeFetch = window.fetch.bind(window)
          let staleScheduled = false
          window.fetch = (input, init) => {
            const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
            if (url.includes('/api/tasks/task-loading')) {
              return new Promise(resolve => window.setTimeout(() => resolve(nativeFetch(input, init)), 900))
            }
            if (!staleScheduled && url.includes('/api/tasks/task-stale-first')) {
              staleScheduled = true
              window.setTimeout(() => {
                history.pushState({}, '', '/tasks/task-stale-latest')
                window.dispatchEvent(new PopStateEvent('popstate'))
              }, 20)
              return new Promise(resolve => window.setTimeout(() => resolve(nativeFetch(input, init)), 260))
            }
            return nativeFetch(input, init)
          }
        })()""")
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
        page.route("**/api/**", handle_api)

        # Loading must be a real intermediate state, never an empty-state flash.
        print("checking loading", flush=True)
        page.goto(f"{BASE_URL}/tasks/task-loading", wait_until="commit")
        page.locator('p[role="status"]').wait_for(state="visible")
        assert not page.locator('[data-state="empty"]').is_visible()
        screenshot(page, "loading-1440x820.png")
        wait_ready(page)

        # Local error preserves page identity and allows keyboard recovery.
        print("checking error recovery", flush=True)
        page.goto(f"{BASE_URL}/tasks/task-error", wait_until="networkidle")
        error_retry = page.get_by_role("button", name="重试加载")
        error_retry.wait_for(state="visible")
        assert page.get_by_role("heading", name="生成任务详情").is_visible()
        assert_visible_focus(page, 'button')
        screenshot(page, "error-recovery-1440x820.png")
        error_retry.focus()
        page.keyboard.press("Enter")
        wait_ready(page)
        assert counters["error"] == 2

        # A successful null detail is deliberately distinct from loading and failure.
        print("checking empty", flush=True)
        page.goto(f"{BASE_URL}/tasks/task-empty", wait_until="networkidle")
        page.locator('[data-state="empty"]').wait_for(state="visible")
        screenshot(page, "empty-1440x820.png")
        page.get_by_role("button", name="重新加载").focus()
        page.keyboard.press("Enter")
        page.locator('[data-state="empty"]').wait_for(state="visible")

        # AUDITING communicates the freeze boundary and tests dialog focus/keyboard behavior.
        print("checking auditing dialog", flush=True)
        page.goto(f"{BASE_URL}/tasks/task-auditing", wait_until="networkidle")
        wait_ready(page)
        assert page.get_by_label("状态：审查中").is_visible()
        assert page.get_by_text("尚未冻结", exact=True).first.is_visible()
        assert page.get_by_text("等待功能冻结", exact=True).is_visible()
        cancel = page.get_by_role("button", name="取消任务")
        cancel.focus()
        page.keyboard.press("Enter")
        dialog = page.get_by_role("dialog")
        dialog.wait_for(state="visible")
        page.wait_for_function("document.activeElement?.closest('dialog') !== null")
        page.keyboard.press("Tab")
        assert page.evaluate("document.activeElement?.closest('dialog') !== null")
        page.keyboard.press("Escape")
        assert dialog.is_visible()
        page.get_by_role("button", name="返回详情").focus()
        page.keyboard.press("Enter")
        assert not dialog.is_visible()
        assert page.evaluate("document.activeElement?.textContent?.trim()") == "取消任务"
        screenshot(page, "auditing-freeze-boundary-1440x820.png")

        # A failed cancellation retains its confirmation context and a second explicit action recovers.
        print("checking cancel recovery", flush=True)
        page.goto(f"{BASE_URL}/tasks/task-cancel-recovery", wait_until="networkidle")
        wait_ready(page)
        page.get_by_role("button", name="取消任务").click()
        dialog = page.get_by_role("dialog")
        confirm_cancel = dialog.get_by_role("button", name="确认取消")
        confirm_cancel.evaluate("button => { button.click(); button.click() }")
        dialog.get_by_role("alert").wait_for(state="visible")
        assert "任务请求失败" in dialog.inner_text()
        assert counters["cancel"] == 1
        dialog.get_by_role("button", name="确认取消").click()
        assert counters["cancel"] == 2
        page.get_by_label("状态：已取消").wait_for(state="visible")

        # PARTIAL exposes only a batch retry, keeps the dialog after failure, then recovers explicitly.
        print("checking retry recovery", flush=True)
        page.goto(f"{BASE_URL}/tasks/task-partial-recovery", wait_until="networkidle")
        wait_ready(page)
        assert page.get_by_label("状态：部分完成").is_visible()
        assert page.get_by_text("生成服务暂不可用").is_visible()
        page.get_by_role("button", name="重试失败批次").click()
        dialog = page.get_by_role("dialog")
        dialog.get_by_role("button", name="确认重试").click()
        dialog.get_by_role("alert").wait_for(state="visible")
        assert "任务请求失败" in dialog.inner_text()
        dialog.get_by_role("button", name="确认重试").click()
        assert counters["retry"] == 2
        page.get_by_label("状态：已完成").wait_for(state="visible")

        # A discovery failure offers the business retry wording rather than a nonexistent batch retry.
        print("checking discovery failure", flush=True)
        page.goto(f"{BASE_URL}/tasks/task-discovery-failed", wait_until="networkidle")
        wait_ready(page)
        page.get_by_role("button", name="重新识别并生成").click()
        dialog = page.get_by_role("dialog")
        assert "确认重新识别材料并生成测试用例" in dialog.inner_text()
        page.get_by_role("button", name="返回详情").click()

        # Same-component route changes must ignore a later stale response from the old task.
        print("checking stale response", flush=True)
        page.goto(f"{BASE_URL}/tasks/task-stale-first", wait_until="domcontentloaded")
        page.get_by_text("最新任务结果", exact=True).wait_for(state="visible")
        page.wait_for_timeout(350)
        assert not page.get_by_text("过期的旧任务结果", exact=True).count()

        # Completed visual acceptance at the required PC canvases.
        print("checking viewports", flush=True)
        page.goto(f"{BASE_URL}/tasks/task-completed", wait_until="networkidle")
        wait_ready(page)
        for width, height, name in [
            (1024, 768, "completed-1024x768.png"),
            (1440, 820, "completed-1440x820.png"),
            (1920, 1080, "completed-1920x1080.png"),
        ]:
            page.set_viewport_size({"width": width, "height": height})
            assert_no_horizontal_overflow(page)
            assert page.get_by_role("heading", name="生成任务详情").is_visible()
            assert page.get_by_role("link", name="下载 Excel").is_visible()
            screenshot(page, name)

        body_text = page.locator("body").inner_text().lower()
        for forbidden in ["task-internal-id-778", "artifact-internal-id-991", "kee-document-should-not-render",
                          "kee-cursor-should-not-render", "cursor-should-not-render", "trace-should-not-render"]:
            assert forbidden not in body_text, forbidden
        unexpected_console_errors = [error for error in console_errors if "503" not in error]
        assert not unexpected_console_errors, unexpected_console_errors
        browser.close()

    (RUN_ROOT / "report.md").write_text(
        "# 任务详情浏览器验收\n\n"
        "- 已通过：loading、error/retry、empty、AUDITING 冻结前、COMPLETED、PARTIAL、FAILED。\n"
        "- 已通过：取消和重试失败后保留对话框上下文，并通过第二次明确操作恢复。\n"
        "- 已通过：1024x768、1440x820、1920x1080 无页面横向溢出；截图同目录。\n"
        "- 已通过：Tab/Enter、可见焦点、原生 dialog 焦点约束与返回焦点、过期响应忽略。\n"
        "- 已通过：mock 中的 KEE/游标/原始追踪等技术标识不出现在用户可见文本。\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
