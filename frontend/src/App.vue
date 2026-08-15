<template>
  <!-- [Req-ID]: REQ-WEB-001, REQ-WEB-007 -->
  <!-- [Req-ID]: REQ-UIX-001, REQ-UIX-002, REQ-UIX-006 -->
  <a
    class="skip-link"
    href="#main-content"
  >跳到主内容</a>
  <div class="app-shell">
    <div
      class="app-shell__ambient"
      aria-hidden="true"
    />
    <header class="app-shell__header">
      <nav
        class="app-shell__nav"
        aria-label="主导航"
      >
        <RouterLink
          class="app-shell__brand"
          to="/"
          aria-label="测试用例智能生成，返回开始生成"
        >
          <span class="app-shell__brand-mark">TC</span>
          <span>
            <strong>测试用例智能生成</strong>
            <small>TEST CASE OPERATIONS</small>
          </span>
        </RouterLink>
        <div class="app-shell__nav-links">
          <RouterLink
            to="/"
            active-class="app-shell__nav-link--active"
            exact-active-class="app-shell__nav-link--active"
            class="app-shell__nav-link"
          >
            开始生成
          </RouterLink>
          <RouterLink
            to="/tasks"
            active-class="app-shell__nav-link--active"
            class="app-shell__nav-link"
          >
            共享任务
          </RouterLink>
        </div>
        <p class="app-shell__service-context">
          <span aria-hidden="true" />后台执行 · 任务可追踪
        </p>
      </nav>
    </header>

    <main
      id="main-content"
      class="app-shell__main"
      tabindex="-1"
    >
      <RouterView />
    </main>
  </div>
</template>
