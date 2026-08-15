import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'

import HomeView from '../views/HomeView.vue'
import TaskListView from '../views/TaskListView.vue'
import TaskDetailView from '../views/TaskDetailView.vue'

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  return createRouter({
    history,
    routes: [
      {
        path: '/',
        name: 'generation-workspace',
        component: HomeView,
      },
      {
        path: '/tasks',
        name: 'task-list',
        component: TaskListView,
      },
      {
        path: '/tasks/:taskId',
        name: 'task-detail',
        component: TaskDetailView,
        props: true,
      },
    ],
  })
}
