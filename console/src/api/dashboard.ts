import { httpGet } from '@/utils/request'

export interface DashboardStats {
  totalTeams: number
  totalQuotas: number
  totalUsers: number
  totalApiKeys: number
  todayTokens: number
  todayCalls: number
  todayCost: number
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
  return httpGet<DashboardStats>('/v1/admin/dashboard/stats')
}

export function getDashboardTrend(days = 7): Promise<TrendPoint[]> {
  return httpGet<TrendPoint[]>('/v1/admin/dashboard/trend', { days })
}

export function getTopTeams(topN = 5): Promise<TopTeam[]> {
  return httpGet<TopTeam[]>('/v1/admin/dashboard/top-teams', { topN })
}
