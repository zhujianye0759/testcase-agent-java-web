"""Scoped browser acceptance for the mocked shared task workflow.

[Req-ID]: REQ-WEB-001, REQ-WEB-002, REQ-WEB-003, REQ-WEB-005, REQ-WEB-006, REQ-WEB-007
"""

import json
from pathlib import Path

from playwright.sync_api import sync_playwright


DETAIL = {
    "id": "task-123",
    "taskMode": "ALL",
    "status": "COMPLETED",
    "totalBatches": 1,
    "completedBatches": 1,
    "artifactReady": True,
    "artifactId": "artifact-456",
    "artifactSha256": "a" * 64,
    "frozenScope": {
        "state": "FROZEN",
        "materialCategory": "admission_material",
        "admissionType": "requirements_spec",
        "documentCount": 1,
        "fingerprint": "f" * 64,
    },
    "batches": [{"featureId": "feature-login", "status": "ACCEPTED"}],
    "features": [{"id": "feature-login", "name": "用户登录", "status": "CONFIRMED"}],
    "cases": [],
    "candidateIssues": [],
    "evidence": [],
    "fewShotUsage": {"policy": "AUTO", "usedExampleCount": 2},
    "warnings": [],
}

PAGE = {
    "items": [{
        "id": "task-123",
        "taskMode": "ALL",
        "status": "COMPLETED",
        "createdAt": "2026-08-14T00:00:00Z",
        "totalBatches": 1,
        "completedBatches": 1,
        "artifactReady": True,
    }],
    "page": 0,
    "size": 20,
    "totalItems": 1,
}


def main() -> None:
    screenshot_root = Path(__file__).resolve().parents[2] / ".comet" / "runs"
    screenshot_root.mkdir(parents=True, exist_ok=True)
    failed_list_once = False
    submitted_task = None

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 820})
        console_errors = []
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)

        def fulfill_api(route):
            nonlocal failed_list_once, submitted_task
            request = route.request
            if request.method == "GET" and request.url.endswith("/api/task-options"):
                route.fulfill(
                    status=200,
                    content_type="application/json",
                    body=json.dumps({"scopeOptions": [{"id": "scope-1", "label": "战略运管 V1.0 准入材料"}]}),
                )
            elif request.method == "POST" and request.url.endswith("/api/tasks"):
                submitted_task = json.loads(request.post_data)
                route.fulfill(status=201, content_type="application/json", body=json.dumps({"id": "task-123"}))
            elif request.method == "GET" and "/api/tasks?" in request.url:
                if "query=failure" in request.url and not failed_list_once:
                    failed_list_once = True
                    route.fulfill(status=503, content_type="application/json", body=json.dumps({"message": "temporary failure"}))
                elif "query=missing" in request.url:
                    route.fulfill(status=200, content_type="application/json", body=json.dumps({**PAGE, "items": [], "totalItems": 0}))
                else:
                    route.fulfill(status=200, content_type="application/json", body=json.dumps(PAGE))
            elif request.method == "GET" and request.url.endswith("/api/tasks/task-123"):
                route.fulfill(status=200, content_type="application/json", body=json.dumps(DETAIL))
            elif request.method == "GET" and request.url.endswith("/api/artifacts/artifact-456/download"):
                route.fulfill(
                    status=200,
                    headers={
                        "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "Content-Disposition": "attachment; filename=\"test-cases.xlsx\"",
                    },
                    body=b"PK\x03\x04mocked-xlsx",
                )
            else:
                route.continue_()

        page.route("**/api/**", fulfill_api)
        page.goto("http://127.0.0.1:5174/", wait_until="networkidle")
        assert page.locator('input[name="taskMode"][value="ALL"]').is_checked()
        assert page.locator('[data-testid="scope-summary"]').inner_text() == "战略运管 V1.0 准入材料"
        assert not page.locator('select[name="scopeOptionId"]').count()
        assert not page.locator('input[name="featureId"]').count()
        assert page.get_by_text("自动参考优质示例（推荐）").is_visible()

        for width, height, name in [(1440, 820, "home-1440x820.png"), (1024, 768, "home-1024x768.png"), (1920, 1080, "home-1920x1080.png")]:
            page.set_viewport_size({"width": width, "height": height})
            page.screenshot(path=str(screenshot_root / name), full_page=True)

        submit = page.locator('button[type="submit"]')
        submit.focus()
        page.keyboard.press("Enter")
        page.wait_for_url("**/tasks/task-123")
        assert submitted_task == {
            "taskMode": "ALL",
            "featureDescription": "",
            "fewShotPolicy": "AUTO",
            "schemaVersion": "1.0",
            "promptVersion": "1.0",
            "scopeOptionId": "scope-1",
            "prompt": "",
        }
        page.locator("dl.task-detail__summary").wait_for(state="visible")
        assert page.get_by_label("状态：已完成").is_visible()
        assert "artifactPath" not in page.content()
        assert "requirement-kb" not in page.content()
        download_link = page.locator('a[download]')
        assert download_link.get_attribute("href") == "/api/artifacts/artifact-456/download"
        with page.expect_download() as download_info:
            download_link.click()
        assert download_info.value.failure() is None

        page.get_by_role("link", name="共享任务").click()
        page.wait_for_url("**/tasks")
        page.get_by_text("共 1 个任务").wait_for(state="visible")
        task_query = page.locator('input[name="taskQuery"]')
        task_query.fill("missing")
        page.get_by_role("button", name="查询").click()
        page.locator('[data-state="no-results"]').wait_for(state="visible")
        page.get_by_role("button", name="重置筛选").click()
        page.get_by_text("共 1 个任务").wait_for(state="visible")

        task_query.fill("failure")
        page.get_by_role("button", name="查询").click()
        retry = page.get_by_role("button", name="重试加载")
        retry.wait_for(state="visible")
        retry.focus()
        page.keyboard.press("Enter")
        page.get_by_text("共 1 个任务").wait_for(state="visible")

        for width, height, name in [(1440, 820, "ui-design-1440x820.png"), (1024, 768, "ui-narrow-1024x768.png"), (1920, 1080, "ui-wide-1920x1080.png")]:
            page.set_viewport_size({"width": width, "height": height})
            page.screenshot(path=str(screenshot_root / name), full_page=True)

        unexpected_console_errors = [error for error in console_errors if "503" not in error]
        assert not unexpected_console_errors, unexpected_console_errors
        browser.close()


if __name__ == "__main__":
    main()
