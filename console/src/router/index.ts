import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'

// 角色常量（PRD V4.0：收敛为三角色）
export const ROLES = {
  ADMIN: 'ADMIN',
  TEAM_ADMIN: 'TEAM_ADMIN',
  USER: 'USER'
} as const

export type Role = (typeof ROLES)[keyof typeof ROLES]

/** 管理端可见角色（排除普通 USER） */
const ADMIN_ROLES = [ROLES.ADMIN, ROLES.TEAM_ADMIN]
/** 仅全局管理员 */
const SUPER_ROLES = [ROLES.ADMIN]
/** 所有角色 */
const ALL_ROLES = [ROLES.ADMIN, ROLES.TEAM_ADMIN, ROLES.USER]

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: () => {
      const userStore = useUserStore()
      return userStore.role === ROLES.USER ? '/my/overview' : '/dashboard'
    },
    children: [
      // ========== 我的中心（所有角色可用） ==========
      {
        path: 'my/overview',
        name: 'MyOverview',
        component: () => import('@/views/my/overview.vue'),
        meta: { title: '我的概览', roles: ALL_ROLES }
      },
      {
        path: 'my/quota',
        name: 'MyQuota',
        component: () => import('@/views/my/quota.vue'),
        meta: { title: '我的额度', roles: ALL_ROLES }
      },
      {
        path: 'my/usage',
        name: 'MyUsage',
        component: () => import('@/views/my/usage.vue'),
        meta: { title: '我的用量', roles: ALL_ROLES }
      },
      {
        path: 'my/bills',
        name: 'MyBills',
        component: () => import('@/views/my/bills.vue'),
        meta: { title: '我的账单', roles: ALL_ROLES }
      },
      {
        path: 'my/transactions',
        name: 'MyTransactions',
        component: () => import('@/views/my/transactions.vue'),
        meta: { title: '我的流水', roles: ALL_ROLES }
      },
      {
        path: 'my/api-keys',
        name: 'MyApiKeys',
        component: () => import('@/views/my/api-keys.vue'),
        meta: { title: '我的 API Key', roles: ALL_ROLES }
      },

      // ========== Quick Start 快速接入（所有角色可用） ==========
      {
        path: 'quickstart',
        name: 'QuickStart',
        component: () => import('@/views/quickstart/index.vue'),
        meta: { title: 'Quick Start', roles: ALL_ROLES }
      },

      // ========== 管理端 ==========
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '概览', roles: ADMIN_ROLES }
      },
      {
        path: 'providers',
        name: 'Provider',
        component: () => import('@/views/provider/index.vue'),
        meta: { title: 'Provider 管理', roles: SUPER_ROLES }
      },
      {
        path: 'teams',
        name: 'Team',
        component: () => import('@/views/team/index.vue'),
        meta: { title: '团队', roles: ADMIN_ROLES }
      },
      {
        path: 'api-keys',
        name: 'ApiKey',
        component: () => import('@/views/apikey/index.vue'),
        meta: { title: 'API Key 管理', roles: ADMIN_ROLES }
      },
      {
        path: 'users',
        name: 'User',
        component: () => import('@/views/user/index.vue'),
        meta: { title: '用户管理', roles: ADMIN_ROLES }
      },
      {
        path: 'quotas',
        name: 'Quota',
        component: () => import('@/views/quota/index.vue'),
        meta: { title: '配额规则', roles: ADMIN_ROLES }
      },
      {
        path: 'usages',
        name: 'Usage',
        component: () => import('@/views/usage/index.vue'),
        meta: { title: '用量统计', roles: ADMIN_ROLES }
      },
      {
        path: 'audits',
        name: 'Audit',
        component: () => import('@/views/audit/index.vue'),
        meta: { title: '审计日志', roles: SUPER_ROLES }
      },
      {
        path: 'reconciles',
        name: 'Reconcile',
        component: () => import('@/views/reconcile/index.vue'),
        meta: { title: '对账中心', roles: SUPER_ROLES }
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/settings/index.vue'),
        meta: { title: '系统设置', roles: SUPER_ROLES }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/** 角色是否有权访问某路由 */
export function canAccess(role: string | undefined, routeMeta: { roles?: string[] }): boolean {
  if (!role) return false
  const roles = routeMeta.roles
  if (!roles || roles.length === 0) return true
  return roles.includes(role)
}

// 全局前置守卫：登录校验 + 角色访问控制
router.beforeEach((to) => {
  const userStore = useUserStore()
  if (!to.meta.public && !userStore.token) {
    return { name: 'Login' }
  }
  if (to.name === 'Login' && userStore.token) {
    return userStore.role === ROLES.USER ? { name: 'MyOverview' } : { name: 'Dashboard' }
  }
  // 角色无权访问时，重定向到该角色可访问的首页
  if (to.name && !canAccess(userStore.role, to.meta)) {
    return userStore.role === ROLES.USER ? { name: 'MyOverview' } : { name: 'Dashboard' }
  }
  return true
})

export default router
