import { httpGet } from '@/utils/request'

export interface DashboardStats {
  totalTeams: number
  totalQuotas: number
  totalUsers: number
  totalApiKeys: number
  todayTokens: number
  todayCalls: number
  todayCost: number
  /** 今日缓存命中率（%，V5.4） */
  todayCacheHitRate: number
  /** 今日缓存节省金额（本位币，V5.4） */
  todayCacheSavedCost: number
}

export interface TrendPoint {
  date: string
  value: number
}

export interface TopTeam {
  teamCode: string
  teamName: string
  tokens: number
  calls: number
  cost: number
}

export function getDashboardStats(): Promise<DashboardStats> {
  return httpGet<DashboardStats>('/api/admin/dashboard/stats')
}

export function getDashboardTrend(days = 7): Promise<TrendPoint[]> {
  return httpGet<TrendPoint[]>('/api/admin/dashboard/trend', { days })
}

export function getTopTeams(topN = 5): Promise<TopTeam[]> {
  return httpGet<TopTeam[]>('/api/admin/dashboard/top-teams', { topN })
}
