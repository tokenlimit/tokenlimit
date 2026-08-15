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
  return httpPost<LoginResult>('/v1/admin/auth/login', data)
}

export function logout(): Promise<void> {
  return httpPost<void>('/v1/admin/auth/logout')
}

export function getProfile(): Promise<LoginResult> {
  return httpGet<LoginResult>('/v1/admin/auth/profile')
}

export function changePassword(data: ChangePasswordParams): Promise<void> {
  return httpPost<void>('/v1/admin/auth/change-password', data)
}
