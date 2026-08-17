import { httpGet } from '@/utils/request'
import type { Team } from './team'
import type { ApiKey } from './apikey'
import type { User } from './user'

export interface MetaAll {
  teams: Team[]
  apiKeys: ApiKey[]
  users: User[]
  targetTypes: string[]
  teamTypes: string[]
  userTypes: string[]
  quotaModes: string[]
  roles: string[]
  apiKeyStatuses: string[]
  limitTypes: string[]
  periods: string[]
  auditEventTypes: string[]
}

export function getMetaAll(): Promise<MetaAll> {
  return httpGet<MetaAll>('/api/admin/meta/all')
}

export function getTeams(params?: { teamType?: string }): Promise<Team[]> {
  return httpGet<Team[]>('/api/admin/meta/teams', params)
}

export function getApiKeys(params?: {
  teamCode?: string
  userCode?: string
}): Promise<ApiKey[]> {
  return httpGet<ApiKey[]>('/api/admin/meta/api-keys', params)
}

export function getUsers(params?: { teamCode?: string }): Promise<User[]> {
  return httpGet<User[]>('/api/admin/meta/users', params)
}
