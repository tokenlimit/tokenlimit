import { httpGet, type PageResult } from '@/utils/request'
import type { UsageLog } from './usage'
import type { ApiKey } from './apikey'

/** 我的概览 */
export interface MyOverview {
  teamCode?: string
  teamName?: string
  userCode?: string
  userName?: string
  quotaMode?: string
  /** 个人配额上限 */
  personalQuota: number
  /** 个人已用 */
  personalUsed: number
  /** 团队配额上限 */
  teamQuota: number
  /** 团队已用 */
  teamUsed: number
  todayTokens: number
  todayCalls: number
  monthTokens: number
  monthCost: number
}

/** 我的额度明细 */
export interface MyQuotaItem {
  targetType: string
  targetCode: string
  model?: string
  limitType: string
  limitValue: number
  period: string
  used: number
  remain: number
}

/** 我的流水 */
export interface MyTransaction {
  id: number
  traceId: string
  model: string
  totalTokens: number
  cost: number
  consumeFrom: string
  status: string
  createdAt: string
}

/** 我的账单 */
export interface MyBill {
  period: string
  totalTokens: number
  totalCost: number
  callCount: number
}

export function getMyOverview(): Promise<MyOverview> {
  return httpGet<MyOverview>('/v1/my/overview')
}

export function getMyQuota(): Promise<MyQuotaItem[]> {
  return httpGet<MyQuotaItem[]>('/v1/my/quota')
}

export function listMyUsages(params?: {
  page?: number
  size?: number
  startTime?: string
  endTime?: string
}): Promise<PageResult<UsageLog>> {
  return httpGet<PageResult<UsageLog>>('/v1/my/usage', params)
}

export function listMyTransactions(params?: {
  page?: number
  size?: number
}): Promise<PageResult<MyTransaction>> {
  return httpGet<PageResult<MyTransaction>>('/v1/my/transactions', params)
}

export function listMyBills(): Promise<MyBill[]> {
  return httpGet<MyBill[]>('/v1/my/bills')
}

export function listMyApiKeys(params?: {
  page?: number
  size?: number
}): Promise<PageResult<ApiKey>> {
  return httpGet<PageResult<ApiKey>>('/v1/my/api-keys', params)
}
