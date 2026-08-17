import { httpDelete, httpGet, httpPost, httpPut, type PageResult } from '@/utils/request'

export interface Team {
  id?: number
  teamCode?: string
  teamName?: string
  teamType?: string
  description?: string
  status?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export function listTeams(params?: {
  page?: number
  size?: number
  teamType?: string
  keyword?: string
  status?: string
}): Promise<PageResult<Team>> {
  return httpGet<PageResult<Team>>('/api/admin/teams', params)
}

export function getTeam(id: number): Promise<Team> {
  return httpGet<Team>(`/api/admin/teams/${id}`)
}

export function createTeam(data: Team): Promise<Team> {
  return httpPost<Team>('/api/admin/teams', data)
}

export function updateTeam(id: number, data: Team): Promise<Team> {
  return httpPut<Team>(`/api/admin/teams/${id}`, data)
}

export function deleteTeam(id: number): Promise<void> {
  return httpDelete<void>(`/api/admin/teams/${id}`)
}

export function changeTeamStatus(id: number, status: string): Promise<void> {
  return httpPut<void>(`/api/admin/teams/${id}/status`, undefined, { status })
}
