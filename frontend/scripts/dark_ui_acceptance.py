"""Visual acceptance for the immersive dark mission-control UI.

Captures creation, list, and detail pages at 1024x768, 1440x820, and
1920x1080 with mocked APIs, plus keyboard-focus and reduced-motion evidence.

[Req-ID]: REQ-UIX-009
"""

import json
import os
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright


BASE_URL = os.environ.get("DARK_UI_BASE_URL", "http://127.0.0.1:5174")

CATALOG = {
    "scopeCatalog": {
        "knowledgeBases": [{
            "id": "kb-safe",
            "label": "战略运管知识库",
            "systems": [{
                "id": "system-safe",
                "label": "战略运管系统",
                "versions": [{
                    "id": "version-safe",
                    "label": "V2.0",
                    "materialTypes": [
                        {"id": "scope-1", "label": "功能清单", "documentCount": 3},
                        {"id": "scope-2", "label": "需求规格说明书", "documentCount": 2},
                    ],
                }],
            }],
        }],
    },
}

PAGE = {
    "items": [
        {"id": "task-live", "taskMode": "ALL", "status": "GENERATING", "createdAt": "2026-08-18T09:12:00Z",
         "totalBatches": 12, "completedBatches": 7, "artifactReady": False},
        {"id": "task-123", "taskMode": "FEATURE", "status": "COMPLETED", "createdAt": "2026-08-17T16:40:00Z",
         "totalBatches": 2, "completedBatches": 2, "artifactReady": True},
        {"id": "task-partial", "taskMode": "ALL", "status": "PARTIAL", "createdAt": "2026-08-17T11:05:00Z",
         "totalBatches": 9, "completedBatches": 8, "artifactReady": True,
         "failureSummary": "1 个批次在三次尝试后仍未通过校验"},
        {"id": "task-failed", "taskMode": "FEATURE", "status": "FAILED", "createdAt": "2026-08-16T14:22:00Z",
         "totalBatches": 0, "completedBatches": 0, "artifactReady": False,
         "failureSummary": "材料遍历门禁未通过：解析单元计数不一致"},
        {"id": "task-queued", "taskMode": "ALL", "status": "QUEUED", "createdAt": "2026-08-16T08:30:00Z",
         "totalBatches": 0, "completedBatches": 0, "artifactReady": False},
    ],
    "page": 0,
    "size": 20,
    "totalItems": 5,
}

DETAIL = {
    "id": "task-123",
    "taskMode": "ALL",
    "status": "COMPLETED",
    "totalBatches": 2,
    "completedBatches": 2,
    "artifactReady": True,
    "artifactId": "artifact-456",
    "frozenScope": {
        "state": "FROZEN",
        "materialCategory": "admission_material",
        "admissionType": "requirements_spec",
        "documentCount": 2,
    },
    "batches": [{"featureId": "feature-login", "status": "ACCEPTED"}],
    "auditRows": [
        {"sequence": 1, "subjectOrFeature": "用户登录", "issueCategory": "描述质量问题",
         "evidenceComparison": "登录失败后的提示方式未明确。"},
        {"sequence": 2, "subjectOrFeature": "密码重置", "issueCategory": "证据不足",
         "evidenceComparison": "功能清单列出密码重置，需求规格说明未给出对应规则。"},
    ],
    "testCaseRows": [
        {"caseName": "用户正常登录", "featureModule": "用户登录", "preconditions": "已创建有效账号",
         "executionSteps": "1. 输入有效账号和密码\n2. 点击登录", "expectedResult": "成功进入首页",
         "requirementContent": "登录功能说明"},
        {"caseName": "错误密码登录被拒绝", "featureModule": "用户登录", "preconditions": "已创建有效账号",
         "executionSteps": "1. 输入有效账号和错误密码\n2. 点击登录", "expectedResult": "提示账号或密码错误",
         "requirementContent": "登录功能说明"},
    ],
    "businessProgress": {
        "currentBusinessStage": "已完成",
        "materialDocumentTotal": 2,
        "completeMaterialDocumentCount": 2,
        "materialUnitTotal": 4,
        "processedMaterialUnitCount": 4,
        "totalAuditWork": 8,
        "completedAuditWork": 8,
        "failedAuditWork": 0,
        "featureCandidateTotal": 6,
        "functionListMissingCount": 0,
        "requirementMissingCount": 0,
        "conflictCount": 0,
        "splitCount": 0,
        "mergeCount": 0,
        "insufficientEvidenceCount": 0,
        "frozenComplete": True,
        "frozenFeatureTotal": 5,
        "generationEligibleFrozenFeatureCount": 4,
        "generationIneligibleFrozenFeatureCount": 1,
        "expectedTestCaseTotal": 8,
        "acceptedTestCaseCount": 8,
        "businessReason": "材料审查、功能冻结和测试用例均已完成",
    },
}


def route_api(route):
    request = route.request
    path = urlparse(request.url).path
    if not path.startswith("/api/"):
        route.continue_()
        return
    if request.method == "GET" and path == "/api/task-options":
        route.fulfill(status=200, content_type="application/json", body=json.dumps(CATALOG))
    elif request.method == "GET" and path == "/api/tasks/task-123":
        route.fulfill(status=200, content_type="application/json", body=json.dumps(DETAIL))
    elif request.method == "GET" and path == "/api/tasks":
        route.fulfill(status=200, content_type="application/json", body=json.dumps(PAGE))
    else:
        route.fulfill(status=404, content_type="application/json", body=json.dumps({"message": "not mocked"}))


def assert_no_horizontal_overflow(page, label):
    overflow = page.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth")
    assert overflow <= 0, f"{label}: horizontal overflow of {overflow}px"


def main() -> None:
    screenshot_root = Path(__file__).resolve().parents[2] / ".comet" / "runs" / "dark-ui"
    screenshot_root.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 820})
        console_errors = []
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
        page.route("**/api/**", route_api)

        # Creation page: ready state, selected mode card, focus evidence.
        page.goto(f"{BASE_URL}/", wait_until="networkidle")
        page.locator('[data-testid="scope-summary"]').wait_for(state="visible")
        page.wait_for_timeout(500)
        assert_no_horizontal_overflow(page, "home-1440")
        page.screenshot(path=str(screenshot_root / "home-1440x820.png"), full_page=True)

        # Mission-control structure: decorative radar sweeps, sticky launch bar docks on scroll.
        radar = page.locator(".generation-workspace__hero-visual")
        assert radar.get_attribute("aria-hidden") == "true", "hero radar must stay decorative"
        sweep = radar.locator("span").evaluate("(el) => getComputedStyle(el).animationName")
        assert "radar-sweep" in sweep, f"hero radar should sweep by default, got {sweep}"

        actions = page.locator(".task-form__actions")
        assert actions.evaluate("(el) => getComputedStyle(el).position") == "sticky", "launch bar must be sticky"
        page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
        page.wait_for_timeout(300)
        box = actions.bounding_box()
        assert box and box["y"] >= 0 and box["y"] + box["height"] <= 820, f"sticky launch bar escaped the viewport: {box}"
        page.screenshot(path=str(screenshot_root / "home-scrolled-1440x820.png"))
        page.evaluate("window.scrollTo(0, 0)")

        page.locator('input[name="taskMode"][value="FEATURE"]').click()
        page.locator('input[name="featureDescription"]').fill("用户登录与忘记密码")
        page.wait_for_timeout(300)
        page.screenshot(path=str(screenshot_root / "home-feature-1440x820.png"), full_page=True)

        page.locator('button[type="submit"]').focus()
        page.wait_for_timeout(200)
        page.screenshot(path=str(screenshot_root / "home-focus-1440x820.png"))

        animation = page.locator(".app-shell__ambient").evaluate("(el) => getComputedStyle(el).animationName")
        assert animation != "none", "ambient animation should run by default"

        # Shared task list: status chip variety.
        page.goto(f"{BASE_URL}/tasks", wait_until="networkidle")
        page.get_by_text("共 5 个任务").wait_for(state="visible")
        page.wait_for_timeout(400)
        assert_no_horizontal_overflow(page, "tasks-1440")
        page.screenshot(path=str(screenshot_root / "tasks-1440x820.png"), full_page=True)

        # Task detail: stage flow, cards, gradient download action.
        page.goto(f"{BASE_URL}/tasks/task-123", wait_until="networkidle")
        page.locator("dl.task-detail__summary").wait_for(state="visible")
        page.wait_for_timeout(400)
        assert_no_horizontal_overflow(page, "detail-1440")
        page.screenshot(path=str(screenshot_root / "detail-1440x820.png"), full_page=True)

        # Narrow and wide PC viewports across the workflow.
        for width, height, name in [(1024, 768, "1024x768"), (1920, 1080, "1920x1080")]:
            page.set_viewport_size({"width": width, "height": height})
            page.goto(f"{BASE_URL}/", wait_until="networkidle")
            page.locator('[data-testid="scope-summary"]').wait_for(state="visible")
            page.wait_for_timeout(400)
            assert_no_horizontal_overflow(page, f"home-{name}")
            page.screenshot(path=str(screenshot_root / f"home-{name}.png"), full_page=True)
            page.goto(f"{BASE_URL}/tasks", wait_until="networkidle")
            page.get_by_text("共 5 个任务").wait_for(state="visible")
            page.wait_for_timeout(300)
            assert_no_horizontal_overflow(page, f"tasks-{name}")
            page.screenshot(path=str(screenshot_root / f"tasks-{name}.png"), full_page=True)
            page.goto(f"{BASE_URL}/tasks/task-123", wait_until="networkidle")
            page.locator("dl.task-detail__summary").wait_for(state="visible")
            page.wait_for_timeout(300)
            assert_no_horizontal_overflow(page, f"detail-{name}")
            page.screenshot(path=str(screenshot_root / f"detail-{name}.png"), full_page=True)

        # Reduced motion: ambience and pulses become static while content stays readable.
        reduced = browser.new_page(viewport={"width": 1440, "height": 820}, reduced_motion="reduce")
        reduced.route("**/api/**", route_api)
        reduced.goto(f"{BASE_URL}/", wait_until="networkidle")
        reduced.locator('[data-testid="scope-summary"]').wait_for(state="visible")
        reduced.wait_for_timeout(400)
        animation = reduced.locator(".app-shell__ambient").evaluate("(el) => getComputedStyle(el).animationName")
        assert animation == "none", f"ambient animation must be removed under reduced motion, got {animation}"
        sweep = reduced.locator(".generation-workspace__hero-visual span").evaluate("(el) => getComputedStyle(el).animationName")
        assert sweep == "none", f"radar sweep must stop under reduced motion, got {sweep}"
        reduced.screenshot(path=str(screenshot_root / "home-reduced-motion-1440x820.png"), full_page=True)

        unexpected = [error for error in console_errors if "404" not in error]
        assert not unexpected, unexpected
        browser.close()

    print(f"dark-ui acceptance evidence written to {screenshot_root}")


if __name__ == "__main__":
    main()
