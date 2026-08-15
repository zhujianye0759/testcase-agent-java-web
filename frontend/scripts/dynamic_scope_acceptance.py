"""Browser acceptance for the real KEE-backed dynamic scope catalog.

[Req-ID]: REQ-CAT-001, REQ-CAT-002, REQ-CAT-004, REQ-WEB-009
"""

import json
import os
import re
from pathlib import Path

from playwright.sync_api import sync_playwright


def main() -> None:
    screenshot_root = Path(__file__).resolve().parents[2] / ".comet" / "runs"
    screenshot_root.mkdir(parents=True, exist_ok=True)
    submitted = None

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 820})
        console_errors = []
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)

        def intercept_task_creation(route):
            nonlocal submitted
            submitted = json.loads(route.request.post_data)
            route.fulfill(status=201, content_type="application/json", body=json.dumps({"id": "scope-acceptance-task"}))

        page.route("**/api/tasks", intercept_task_creation)
        page.route("**/api/tasks/scope-acceptance-task", lambda route: route.fulfill(
            status=200,
            content_type="application/json",
            body=json.dumps({
                "id": "scope-acceptance-task", "taskMode": "ALL", "status": "QUEUED",
                "totalBatches": 0, "completedBatches": 0, "artifactReady": False,
            }),
        ))

        page.goto(os.environ.get("TESTCASE_WEB_URL", "http://127.0.0.1:5175/"), wait_until="networkidle")
        page.locator('[data-testid="scope-summary"]').wait_for(state="visible")
        body = page.locator("body").inner_text()
        assert "战略运管系统 / 战略运管系统 / V1.0" in body
        assert "功能清单" in body and "工单方案" in body
        assert not re.search(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", body, re.I)
        assert page.locator('input[name="materialTypeIds"]').count() == 2
        assert page.locator('button[type="submit"]').is_disabled()

        material_choices = page.locator('input[name="materialTypeIds"]')
        for index in range(material_choices.count()):
            material_choices.nth(index).check()
        assert page.locator('button[type="submit"]').is_enabled()

        for width, height, name in [
            (1440, 820, "dynamic-scope-1440x820.png"),
            (1024, 768, "dynamic-scope-1024x768.png"),
            (1920, 1080, "dynamic-scope-1920x1080.png"),
        ]:
            page.set_viewport_size({"width": width, "height": height})
            assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
            page.screenshot(path=str(screenshot_root / name), full_page=True)

        page.get_by_role("button", name="重新加载材料范围").click()
        page.locator('[data-testid="scope-summary"]').wait_for(state="visible")
        for index in range(material_choices.count()):
            material_choices.nth(index).check()

        submit = page.locator('button[type="submit"]')
        submit.focus()
        page.keyboard.press("Enter")
        page.wait_for_url("**/tasks/scope-acceptance-task")
        assert submitted is not None
        assert submitted["scopeSelectionIds"] and len(submitted["scopeSelectionIds"]) == 2
        assert "scopeOptionId" not in submitted
        assert not console_errors, console_errors
        browser.close()


if __name__ == "__main__":
    main()
