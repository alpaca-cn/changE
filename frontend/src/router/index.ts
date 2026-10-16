import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import type { Component } from 'vue'
import { allRoutes } from '../nav'

import TodayView from '../views/TodayView.vue'
import TrainingView from '../views/TrainingView.vue'
import DietView from '../views/DietView.vue'
import BodyMapView from '../views/BodyMapView.vue'
import ProgressView from '../views/ProgressView.vue'
import BodyDataView from '../views/BodyDataView.vue'
import AgentView from '../views/AgentView.vue'
import SettingsView from '../views/SettingsView.vue'
import AboutView from '../views/AboutView.vue'

// 用 Component 而不是 unknown：unknown 过不了 RouteRecordRaw 的 component 字段（TS2322）。
// 这里**刻意不写成更紧的键联合**（Record<'/today' | ..., Component>）——那样 nav.ts 里
// 拼错 path（比如 /body_map）会在编译期就报错，但路由表就再也无法由 nav.ts 派生。
// 代价是「nav.ts 加了项、忘了在这里加组件」编译期抓不到，改由 router.spec.ts 钉住。
const componentByPath: Record<string, Component> = {
  '/today': TodayView,
  '/training': TrainingView,
  '/diet': DietView,
  '/body-map': BodyMapView,
  '/progress': ProgressView,
  '/body': BodyDataView,
  '/agent': AgentView,
  '/settings': SettingsView,
  '/about': AboutView,
}

// 路由表由 nav.ts 派生：布局改了这里不用动。
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/today' },
  ...allRoutes().map((item) => ({
    path: item.path,
    name: item.name,
    component: componentByPath[item.path],
  })),
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
