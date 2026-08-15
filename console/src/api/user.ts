import { httpDelete, httpGet, httpPost, httpPut, type PageResult } from '@/utils/request'

export interface User {
  id?: number
  teamCode?: string
  userCode?: string
  userName?: string
  userType?: string
  quotaMode?: string
  /** 角色：USER / TEAM_ADMIN / ADMIN */
  role?: string
  /** 登录账号（全局唯一） */
  username?: string
  /** 是否允许登录 */
  loginEnabled?: boolean
  lastLoginAt?: string
  passwordChangedAt?: string
  status?: string
  createdAt?: string
  updatedAt?: string
}

export interface CreateUserParams {
  teamCode: string
  userCode: string
  userName: string
  userType?: string
  quotaMode?: string
  role?: string
  username?: string
  password?: string
  loginEnabled?: boolean
}

export function listUsers(params?: {
  page?: number
  size?: number
  teamCode?: string
  keyword?: string
  userType?: string
  quotaMode?: string
  role?: string
  status?: string
}): Promise<PageResult<User>> {
  return httpGet<PageResult<User>>('/v1/admin/users', params)
}

export function getUser(id: number): Promise<User> {
  return httpGet<User>(`/v1/admin/users/${id}`)
}

export function createUser(data: CreateUserParams): Promise<User> {
  return httpPost<User>('/v1/admin/users', data)
}

export function updateUser(id: number, data: Partial<User>): Promise<User> {
  return httpPut<User>(`/v1/admin/users/${id}`, data)
}

export function deleteUser(id: number): Promise<void> {
  return httpDelete<void>(`/v1/admin/users/${id}`)
}

export function changeUserStatus(id: number, status: string): Promise<void> {
  return httpPut<void>(`/v1/admin/users/${id}/status`, undefined, { status })
}

export function resetUserPassword(id: number): Promise<{ username: string; password: string }> {
  return httpPost<{ username: string; password: string }>(`/v1/admin/users/${id}/reset-password`)
}
