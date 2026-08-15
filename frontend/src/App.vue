<template>
  <!-- [Req-ID]: REQ-WEB-001 -->
  <!-- [Req-ID]: REQ-WEB-007 -->
  <a
    class="skip-link"
    href="#main-content"
  >跳到主内容</a>
  <div class="app-shell">
    <header>
      <nav aria-label="主导航">
        <RouterLink to="/">
          测试用例智能生成
        </RouterLink>
        <RouterLink to="/tasks">
          共享任务
        </RouterLink>
      </nav>
    </header>

    <main
      id="main-content"
      tabindex="-1"
    >
      <RouterView />
    </main>
  </div>
</template>
