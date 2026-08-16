import { httpDelete, httpGet, httpPost, httpPut, type PageResult } from '@/utils/request'

export interface QuotaRule {
  id?: number
  ruleCode?: string
  /** TEAM / USER */
  targetType?: string
  targetCode?: string
  model?: string
  /** TOKEN / COST / REQUEST_COUNT / RPM / TPM */
  limitType?: string
  limitValue?: number
  /** MINUTE / HOUR / DAY / WEEK / MONTH / YEAR / TOTAL */
  period?: string
  priority?: number
  enabled?: boolean
  description?: string
  createdAt?: string
}

export function listQuotaRules(params?: {
  page?: number
  size?: number
  targetType?: string
  targetCode?: string
  limitType?: string
  period?: string
  keyword?: string
}): Promise<PageResult<QuotaRule>> {
  return httpGet<PageResult<QuotaRule>>('/v1/admin/quota-rules', params)
}

export function getQuotaRule(id: number): Promise<QuotaRule> {
  return httpGet<QuotaRule>(`/v1/admin/quota-rules/${id}`)
}

export function createQuotaRule(data: QuotaRule): Promise<QuotaRule> {
  return httpPost<QuotaRule>('/v1/admin/quota-rules', data)
}

export function updateQuotaRule(id: number, data: QuotaRule): Promise<QuotaRule> {
  return httpPut<QuotaRule>(`/v1/admin/quota-rules/${id}`, data)
}

export function deleteQuotaRule(id: number): Promise<void> {
  return httpDelete<void>(`/v1/admin/quota-rules/${id}`)
}

export function changeQuotaRuleStatus(id: number, enabled: boolean): Promise<void> {
  return httpPut<void>(`/v1/admin/quota-rules/${id}/status`, undefined, { enabled })
}
