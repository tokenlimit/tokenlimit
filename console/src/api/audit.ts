import { httpGet, type PageResult } from '@/utils/request'

export interface AuditLog {
  id?: number
  teamCode?: string
  userCode?: string
  apiKeyId?: string
  operator?: string
  eventType?: string
  targetType?: string
  targetCode?: string
  detail?: string
  result?: string
  traceId?: string
  createdAt?: string
}

export function listAudits(params?: {
  page?: number
  size?: number
  teamCode?: string
  eventType?: string
  targetType?: string
  operator?: string
  result?: string
  startTime?: string
  endTime?: string
}): Promise<PageResult<AuditLog>> {
  return httpGet<PageResult<AuditLog>>('/v1/admin/audits', params)
}

export function getAudit(id: number): Promise<AuditLog> {
  return httpGet<AuditLog>(`/v1/admin/audits/${id}`)
}
