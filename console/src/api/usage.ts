import { httpGet, type PageResult } from '@/utils/request'

export interface UsageLog {
  id?: number
  traceId?: string
  teamCode?: string
  userCode?: string
  apiKeyId?: string
  model?: string
  provider?: string
  estimatedTokens?: number
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
  cost?: number
  consumeFrom?: string
  status?: string
  createdAt?: string
  updatedAt?: string
}

export function listUsages(params?: {
  page?: number
  size?: number
  teamCode?: string
  apiKeyId?: string
  userCode?: string
  model?: string
  status?: string
  startTime?: string
  endTime?: string
}): Promise<PageResult<UsageLog>> {
  return httpGet<PageResult<UsageLog>>('/v1/admin/usages', params)
}

export function getUsage(id: number): Promise<UsageLog> {
  return httpGet<UsageLog>(`/v1/admin/usages/${id}`)
}
