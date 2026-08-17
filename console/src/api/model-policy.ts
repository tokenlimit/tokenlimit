import request from '@/utils/request'

export interface TeamModelPolicy {
  id?: number
  teamCode: string
  model: string | null
  credentialCode: string
  enabled: boolean
  remark?: string
  createdAt?: string
}

export interface ListPolicyParams {
  page: number
  size: number
  teamCode?: string
  model?: string
  keyword?: string
}

export function listModelPolicies(params: ListPolicyParams) {
  return request<{ records: TeamModelPolicy[]; total: number }>({
    url: '/api/admin/model-policies',
    method: 'get',
    params
  })
}

export function createModelPolicy(data: Omit<TeamModelPolicy, 'id' | 'createdAt'>) {
  return request<TeamModelPolicy>({
    url: '/api/admin/model-policies',
    method: 'post',
    data
  })
}

export function updateModelPolicy(id: number, data: Partial<TeamModelPolicy>) {
  return request<TeamModelPolicy>({
    url: `/api/admin/model-policies/${id}`,
    method: 'put',
    data
  })
}

export function deleteModelPolicy(id: number) {
  return request<void>({
    url: `/api/admin/model-policies/${id}`,
    method: 'delete'
  })
}

export function listPolicyCredentials(teamCode?: string) {
  return request<any[]>({
    url: '/api/admin/model-policies/credentials',
    method: 'get',
    params: { teamCode }
  })
}
