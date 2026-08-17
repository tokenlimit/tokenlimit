import { httpGet, httpPost } from '@/utils/request'

export interface LoginParams {
  username: string
  password: string
}

export interface LoginResult {
  /** 仅登录接口返回，profile 接口不返回 */
  token?: string
  username: string
  userName?: string
  /** 角色：ADMIN / TEAM_ADMIN / USER */
  role?: string
  teamCode?: string
  userCode?: string
  /** 是否首次登录需强制改密 */
  mustChangePassword?: boolean
}

export interface ChangePasswordParams {
  oldPassword: string
  newPassword: string
}

export function login(data: LoginParams): Promise<LoginResult> {
  return httpPost<LoginResult>('/api/admin/auth/login', data)
}

export function logout(): Promise<void> {
  return httpPost<void>('/api/admin/auth/logout')
}

export function getProfile(): Promise<LoginResult> {
  return httpGet<LoginResult>('/api/admin/auth/profile')
}

/**
 * 修改密码：JWT 无状态，改密后服务端直接签发新令牌返回（mustChangePassword=false），
 * 前端调用 userStore.apply(result) 替换本地令牌即可即时生效。
 */
export function changePassword(data: ChangePasswordParams): Promise<LoginResult> {
  return httpPost<LoginResult>('/api/admin/auth/change-password', data)
}
