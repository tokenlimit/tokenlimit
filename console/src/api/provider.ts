import { httpDelete, httpGet, httpPost, httpPut, type PageResult } from '@/utils/request'

/** Provider 凭证（PRD V4.0） */
export interface ProviderCredential {
  id?: number
  /** 凭证编码，唯一 */
  credentialCode?: string
  /** 供应商标识，如 openai / azure / deepseek */
  provider?: string
  /** 供应商展示名 */
  providerName?: string
  /** 凭证名称（自定义） */
  credentialName?: string
  /** 作用域：GLOBAL / TEAM */
  scopeType?: string
  /** TEAM 作用域时的团队编码 */
  teamCode?: string
  /** API Base URL */
  apiBaseUrl?: string
  /** 加密后的 Key（仅写入，永不回显） */
  apiKeyEnc?: string
  /** 默认模型 */
  model?: string
  /** ACTIVE / INACTIVE */
  status?: string
  remark?: string
  createdAt?: string
  updatedAt?: string
}

/** 创建/更新凭证请求体 */
export interface CreateCredentialParams {
  credentialCode?: string
  provider: string
  providerName?: string
  credentialName: string
  scopeType: 'GLOBAL' | 'TEAM'
  teamCode?: string
  apiBaseUrl?: string
  apiKey?: string
  model?: string
  status?: string
  remark?: string
}

/** 团队模型策略（team + model → credential） */
export interface TeamModelPolicy {
  id?: number
  teamCode?: string
  model?: string
  credentialCode?: string
  enabled?: boolean
  remark?: string
  createdAt?: string
  updatedAt?: string
}

/** 供应商下拉项 */
export interface ProviderOption {
  provider: string
  providerName: string
}

/** 内置供应商模板（PRD V5.0 §9.7） */
export interface ProviderTemplate {
  provider: string
  providerName: string
  baseUrl?: string
  /** 是否 OpenAI 协议兼容可直传 */
  openAiCompatible?: boolean
  /** 是否需要拼接 Endpoint ID（如火山方舟） */
  requiresEndpoint?: boolean
  /** 是否可直接透传模板（兼容 且 无需拼接 Endpoint） */
  directPassthrough?: boolean
}

export function listProviderCredentials(params?: {
  page?: number
  size?: number
  provider?: string
  scopeType?: string
  teamCode?: string
  keyword?: string
  status?: string
}): Promise<PageResult<ProviderCredential>> {
  return httpGet<PageResult<ProviderCredential>>('/v1/admin/providers', params)
}

export function createProviderCredential(data: CreateCredentialParams): Promise<{
  credentialCode: string
  credentialName: string
  apiKey: string
}> {
  return httpPost('/v1/admin/providers', data)
}

export function updateProviderCredential(credentialCode: string, data: CreateCredentialParams): Promise<ProviderCredential> {
  return httpPut<ProviderCredential>(`/v1/admin/providers/${credentialCode}`, data)
}

export function toggleProviderCredential(credentialCode: string): Promise<ProviderCredential> {
  return httpPost<ProviderCredential>(`/v1/admin/providers/${credentialCode}/toggle`)
}

export function deleteProviderCredential(credentialCode: string): Promise<void> {
  return httpDelete<void>(`/v1/admin/providers/${credentialCode}`)
}

export function listProviderOptions(): Promise<ProviderOption[]> {
  return httpGet<ProviderOption[]>('/v1/admin/providers/providers')
}

/** 内置供应商模板下拉（选择后自动填充 Base URL） */
export function listProviderTemplates(): Promise<ProviderTemplate[]> {
  return httpGet<ProviderTemplate[]>('/v1/admin/providers/templates')
}

export function listModelPolicies(params?: {
  page?: number
  size?: number
  teamCode?: string
  model?: string
  keyword?: string
}): Promise<PageResult<TeamModelPolicy>> {
  return httpGet<PageResult<TeamModelPolicy>>('/v1/admin/model-policies', params)
}

export function createModelPolicy(data: TeamModelPolicy): Promise<TeamModelPolicy> {
  return httpPost<TeamModelPolicy>('/v1/admin/model-policies', data)
}

export function updateModelPolicy(id: number, data: Partial<TeamModelPolicy>): Promise<TeamModelPolicy> {
  return httpPut<TeamModelPolicy>(`/v1/admin/model-policies/${id}`, data)
}

export function deleteModelPolicy(id: number): Promise<void> {
  return httpDelete<void>(`/v1/admin/model-policies/${id}`)
}

/** 模型策略可用的凭证下拉（GLOBAL + 团队专属） */
export function listPolicyCredentials(teamCode?: string): Promise<ProviderCredential[]> {
  return httpGet<ProviderCredential[]>('/v1/admin/model-policies/credentials', { teamCode })
}
