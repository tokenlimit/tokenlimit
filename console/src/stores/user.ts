import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as loginApi, type LoginParams, type LoginResult } from '@/api/auth'

/** V3 → V4 角色映射（兼容历史数据） */
const ROLE_MAP: Record<string, string> = {
  SUPER_ADMIN: 'ADMIN',
  NAMESPACE_ADMIN: 'ADMIN',
  ADMIN: 'ADMIN',
  TEAM_ADMIN: 'TEAM_ADMIN',
  USER: 'USER'
}

export function normalizeRole(role?: string | null): string {
  if (!role) return 'USER'
  return ROLE_MAP[role] || 'USER'
}

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  const username = ref<string>(localStorage.getItem('username') || '')
  const userName = ref<string>(localStorage.getItem('userName') || '')
  const role = ref<string>(normalizeRole(localStorage.getItem('role')))
  const teamCode = ref<string>(localStorage.getItem('teamCode') || '')
  const userCode = ref<string>(localStorage.getItem('userCode') || '')
  const mustChangePassword = ref<boolean>(localStorage.getItem('mustChangePassword') === '1')

  function apply(result: LoginResult) {
    token.value = result.token || ''
    username.value = result.username
    userName.value = result.userName || ''
    role.value = normalizeRole(result.role)
    teamCode.value = result.teamCode || ''
    userCode.value = result.userCode || ''
    mustChangePassword.value = !!result.mustChangePassword
    localStorage.setItem('token', token.value)
    localStorage.setItem('username', username.value)
    localStorage.setItem('userName', result.userName || '')
    localStorage.setItem('role', role.value)
    localStorage.setItem('teamCode', teamCode.value)
    localStorage.setItem('userCode', userCode.value)
    localStorage.setItem('mustChangePassword', mustChangePassword.value ? '1' : '0')
    // 清理 V3 遗留字段
    localStorage.removeItem('namespaceCode')
  }

  async function login(params: LoginParams) {
    const result = await loginApi(params)
    apply(result)
  }

  function logout() {
    token.value = ''
    username.value = ''
    userName.value = ''
    role.value = ''
    teamCode.value = ''
    userCode.value = ''
    mustChangePassword.value = false
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('userName')
    localStorage.removeItem('role')
    localStorage.removeItem('namespaceCode')
    localStorage.removeItem('teamCode')
    localStorage.removeItem('userCode')
    localStorage.removeItem('mustChangePassword')
  }

  return { token, username, userName, role, teamCode, userCode, mustChangePassword, login, logout, apply }
})
