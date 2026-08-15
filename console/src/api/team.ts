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
  return httpGet<PageResult<Team>>('/v1/admin/teams', params)
}

export function getTeam(id: number): Promise<Team> {
  return httpGet<Team>(`/v1/admin/teams/${id}`)
}

export function createTeam(data: Team): Promise<Team> {
  return httpPost<Team>('/v1/admin/teams', data)
}

export function updateTeam(id: number, data: Team): Promise<Team> {
  return httpPut<Team>(`/v1/admin/teams/${id}`, data)
}

export function deleteTeam(id: number): Promise<void> {
  return httpDelete<void>(`/v1/admin/teams/${id}`)
}

export function changeTeamStatus(id: number, status: string): Promise<void> {
  return httpPut<void>(`/v1/admin/teams/${id}/status`, undefined, { status })
}
