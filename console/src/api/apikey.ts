import { httpDelete, httpGet, httpPost, httpPut, type PageResult } from '@/utils/request'

export interface ApiKey {
  id?: number
  teamCode?: string
  userCode?: string
  keyId?: string
  keyName?: string
  accessKey?: string
  secretHash?: string
  status?: string
  expireAt?: string
  lastUsedAt?: string
  createdBy?: string
  /** 允许的模型白名单（逗号分隔，空表示全部，PRD 10.1） */
  allowedModels?: string
  createdAt?: string
  updatedAt?: string
}

export interface CreateApiKeyResult {
  apiKey: ApiKey
  /** 仅创建/重置时返回一次的明文 secret */
  secret: string
}

export function listApiKeys(params?: {
  page?: number
  size?: number
  teamCode?: string
  userCode?: string
  keyword?: string
  status?: string
}): Promise<PageResult<ApiKey>> {
  return httpGet<PageResult<ApiKey>>('/v1/admin/api-keys', params)
}

export function getApiKey(id: number): Promise<ApiKey> {
  return httpGet<ApiKey>(`/v1/admin/api-keys/${id}`)
}

export function createApiKey(data: Partial<ApiKey>): Promise<CreateApiKeyResult> {
  return httpPost<CreateApiKeyResult>('/v1/admin/api-keys', data)
}

export function updateApiKey(id: number, data: Partial<ApiKey>): Promise<ApiKey> {
  return httpPut<ApiKey>(`/v1/admin/api-keys/${id}`, data)
}

export function deleteApiKey(id: number): Promise<void> {
  return httpDelete<void>(`/v1/admin/api-keys/${id}`)
}

export function resetApiKeySecret(id: number): Promise<{ accessKey?: string; secret: string }> {
  return httpPost<{ accessKey?: string; secret: string }>(`/v1/admin/api-keys/${id}/reset-secret`)
}

export function changeApiKeyStatus(id: number, status: string): Promise<void> {
  return httpPut<void>(`/v1/admin/api-keys/${id}/status`, undefined, { status })
}
