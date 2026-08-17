import { httpGet, type PageResult } from '@/utils/request'
import type { UsageLog } from './usage'
import type { ApiKey } from './apikey'

// 当前登录用户视角的“我的”数据接口，归属于 ADMIN 控制台包（/api/api/admin/*）；
// overview/quota/transactions/bills 为 MyAdminController；usage/api-keys 复用现有管理接口（USER 角色自动过滤自己）

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
  return httpGet<MyOverview>('/api/admin/my/overview')
}

export function getMyQuota(): Promise<MyQuotaItem[]> {
  return httpGet<MyQuotaItem[]>('/api/admin/my/quota')
}

export function listMyUsages(params?: {
  page?: number
  size?: number
  startTime?: string
  endTime?: string
}): Promise<PageResult<UsageLog>> {
  // 复用管理端用量接口：USER 角色自动按当前 userCode 过滤（UsageAdminController）
  return httpGet<PageResult<UsageLog>>('/api/admin/usages', params)
}

export function listMyTransactions(params?: {
  page?: number
  size?: number
}): Promise<PageResult<MyTransaction>> {
  return httpGet<PageResult<MyTransaction>>('/api/admin/my/transactions', params)
}

export function listMyBills(): Promise<MyBill[]> {
  return httpGet<MyBill[]>('/api/admin/my/bills')
}

export function listMyApiKeys(params?: {
  page?: number
  size?: number
}): Promise<PageResult<ApiKey>> {
  // 复用管理端 API Key 接口：USER 角色自动按当前 userCode 过滤（ApiKeyAdminController）
  return httpGet<PageResult<ApiKey>>('/api/admin/api-keys', params)
}
