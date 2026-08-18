import { httpGet, httpPost, httpPut, type ApiResult } from '@/utils/request'

/** API Key 限额策略接口（V6.0 User 自助风控） */
export interface ApiKeyPolicy {
  id?: number
  accessKey: string
  teamCode: string
  userCode: string
  keyId: string
  maxTokensPerRequest?: number | null
  hourlyLimit?: number | null
  hourlyUsed?: number
  hourlyResetAt?: string | null
  dailyLimit?: number | null
  dailyUsed?: number
  dailyResetAt?: string | null
  isFrozen?: boolean
  frozenReason?: string | null
  status?: string
  createdAt?: string
  updatedAt?: string
}

/** 更新策略请求 */
export interface UpdatePolicyRequest {
  maxTokensPerRequest?: number | null
  hourlyLimit?: number | null
  dailyLimit?: number | null
}

/** 冻结请求 */
export interface FreezeRequest {
  frozen: boolean
  reason?: string
}

/** 获取我的 API Key 策略列表 */
export function getMyPolicies(): Promise<ApiKeyPolicy[]> {
  return httpGet<ApiKeyPolicy[]>('/api/admin/user-policy/my-policies')
}

/** 获取指定 API Key 的策略详情 */
export function getPolicy(accessKey: string): Promise<ApiKeyPolicy> {
  return httpGet<ApiKeyPolicy>(`/api/admin/user-policy/${accessKey}/policy`)
}

/** 更新用户自定义策略 */
export function updatePolicy(accessKey: string, data: UpdatePolicyRequest): Promise<ApiKeyPolicy> {
  return httpPut<ApiKeyPolicy>(`/api/admin/user-policy/${accessKey}/policy`, data)
}

/** 冻结/解冻 API Key */
export function freezeApiKey(accessKey: string, data: FreezeRequest): Promise<void> {
  return httpPost<void>(`/api/admin/user-policy/${accessKey}/freeze`, data)
}

/** 重置小时用量 */
export function resetHourlyUsage(accessKey: string): Promise<void> {
  return httpPost<void>(`/api/admin/user-policy/${accessKey}/reset-hourly`)
}

/** 重置日用量 */
export function resetDailyUsage(accessKey: string): Promise<void> {
  return httpPost<void>(`/api/admin/user-policy/${accessKey}/reset-daily`)
}
