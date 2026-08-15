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
  return httpGet<MetaAll>('/v1/admin/meta/all')
}

export function getTeams(params?: { teamType?: string }): Promise<Team[]> {
  return httpGet<Team[]>('/v1/admin/meta/teams', params)
}

export function getApiKeys(params?: {
  teamCode?: string
  userCode?: string
}): Promise<ApiKey[]> {
  return httpGet<ApiKey[]>('/v1/admin/meta/api-keys', params)
}

export function getUsers(params?: { teamCode?: string }): Promise<User[]> {
  return httpGet<User[]>('/v1/admin/meta/users', params)
}
