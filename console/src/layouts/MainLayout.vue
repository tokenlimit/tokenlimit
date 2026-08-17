<template>
  <el-container class="main-layout">
    <el-aside width="220px">
      <div class="logo">
        <div class="logo-badge">TL</div>
        <span>TokenLimit</span>
      </div>
      <el-scrollbar class="menu-scroll">
        <el-menu :default-active="activeMenu" router background-color="#304156" text-color="#bfcbd9" active-text-color="#409eff">
          <el-menu-item-group title="我的中心">
            <el-menu-item v-for="item in myMenus" :key="item.index" :index="item.index">
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.title }}</span>
            </el-menu-item>
          </el-menu-item-group>

          <el-menu-item-group title="快速接入">
            <el-menu-item index="/quickstart">
              <el-icon><Promotion /></el-icon>
              <span>Quick Start</span>
            </el-menu-item>
          </el-menu-item-group>

          <template v-if="adminGroups.length > 0">
            <el-menu-item-group v-for="group in adminGroups" :key="group.title" :title="group.title">
              <el-menu-item v-for="item in group.items" :key="item.index" :index="item.index">
                <el-icon><component :is="item.icon" /></el-icon>
                <span>{{ item.title }}</span>
              </el-menu-item>
            </el-menu-item-group>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header class="header">
        <span class="page-title">{{ pageTitle }}</span>
        <div class="header-right">
          <el-tag v-if="roleLabel" size="small" effect="plain" class="role-tag">{{ roleLabel }}</el-tag>
          <el-dropdown @command="handleCommand">
            <span class="user">
              <el-avatar :size="28" class="avatar">{{ avatarText }}</el-avatar>
              {{ userStore.userName || userStore.username }}<el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="my">个人中心</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
  Avatar,
  Coin,
  Connection,
  Document,
  Files,
  Key,
  Odometer,
  Promotion,
  ScaleToOriginal,
  Setting,
  Tickets,
  TrendCharts,
  UserFilled
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { ROLES, canAccess } from '@/router'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const role = computed(() => userStore.role)

const roleLabels: Record<string, string> = {
  [ROLES.ADMIN]: '全局管理员',
  [ROLES.TEAM_ADMIN]: 'Team 管理员',
  [ROLES.USER]: '普通用户'
}
const roleLabel = computed(() => roleLabels[role.value] || '')

interface MenuItem {
  index: string
  icon: unknown
  title: string
}

const myMenus: MenuItem[] = [
  { index: '/my/overview', icon: Odometer, title: '我的概览' },
  { index: '/my/quota', icon: Coin, title: '我的额度' },
  { index: '/my/usage', icon: TrendCharts, title: '我的用量' },
  { index: '/my/bills', icon: Document, title: '我的账单' },
  { index: '/my/transactions', icon: Tickets, title: '我的流水' },
  { index: '/my/api-keys', icon: Key, title: '我的 API Key' }
]

interface MenuGroup {
  title: string
  items: MenuItem[]
}

const allAdminGroups: { roles?: string[]; group: MenuGroup }[] = [
  {
    roles: [ROLES.ADMIN, ROLES.TEAM_ADMIN],
    group: {
      title: '概览',
      items: [{ index: '/dashboard', icon: Odometer, title: '全局概览' }]
    }
  },
  {
    roles: [ROLES.ADMIN, ROLES.TEAM_ADMIN],
    group: {
      title: '资源管理',
      items: [
        { index: '/api-keys', icon: Key, title: 'API Key 管理' },
        { index: '/users', icon: Avatar, title: 'User 用户' }
      ]
    }
  },
  {
    roles: [ROLES.ADMIN],
    group: {
      title: '组织管理',
      items: [{ index: '/teams', icon: UserFilled, title: 'Team 团队' }]
    }
  },
  {
    roles: [ROLES.ADMIN],
    group: {
      title: '接入管理',
      items: [{ index: '/providers', icon: Files, title: 'Provider 管理' }]
    }
  },
  {
    roles: [ROLES.ADMIN, ROLES.TEAM_ADMIN],
    group: {
      title: '治理能力',
      items: [
        { index: '/quotas', icon: Coin, title: '配额规则' },
        { index: '/usages', icon: TrendCharts, title: '用量统计' }
      ]
    }
  },
  {
    roles: [ROLES.ADMIN, ROLES.TEAM_ADMIN],
    group: {
      title: '审计',
      items: [{ index: '/audits', icon: Document, title: '审计日志' }]
    }
  },
  {
    roles: [ROLES.ADMIN],
    group: {
      title: '系统',
      items: [
        { index: '/reconciles', icon: ScaleToOriginal, title: '对账中心' },
        { index: '/policies', icon: Connection, title: '模型策略' },
        { index: '/settings', icon: Setting, title: '系统设置' }
      ]
    }
  }
]

const adminGroups = computed<MenuGroup[]>(() =>
  allAdminGroups
    .filter(({ roles }) => canAccess(role.value, { roles }))
    .map(({ group }) => group)
)

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => (route.meta.title as string) || '')
const avatarText = computed(() => (userStore.userName || userStore.username || 'A').charAt(0).toUpperCase())

function handleCommand(command: string) {
  if (command === 'logout') {
    userStore.logout()
    router.push({ name: 'Login' })
  } else if (command === 'my') {
    router.push({ path: '/my/overview' })
  }
}
</script>

<style scoped lang="scss">
.main-layout {
  height: 100%;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  background: #1f2d3d;

  .logo-badge {
    width: 26px;
    height: 26px;
    border-radius: 6px;
    background: linear-gradient(135deg, #409eff, #00c6ff);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 11px;
    font-weight: 800;
  }
}

.menu-scroll {
  height: calc(100% - 60px);
}

.el-aside {
  background: #304156;

  .el-menu {
    border-right: none;
  }

  :deep(.el-menu-item-group__title) {
    color: rgba(255, 255, 255, 0.35);
    padding: 12px 16px 6px;
  }
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;

  .page-title {
    font-size: 16px;
    font-weight: 500;
  }

  .header-right {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .user {
    display: flex;
    align-items: center;
    gap: 6px;
    cursor: pointer;
  }

  .avatar {
    background: #409eff;
    font-size: 13px;
  }

  .role-tag {
    color: #409eff;
  }
}
</style>
