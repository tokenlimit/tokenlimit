import { httpDelete, httpGet, httpPost, httpPut, type PageResult } from '@/utils/request'

export interface ModelPrice {
  id?: number
  provider?: string
  model?: string
  /** 输入单价（每 Token） */
  inputPricePerToken?: number
  /** 输出单价（每 Token） */
  outputPricePerToken?: number
  /** 缓存读取单价（未配置为 null，按正常输入价计费） */
  cacheReadPricePerToken?: number | null
  /** 缓存写入单价（未配置为 null，按正常输入价计费） */
  cacheWritePricePerToken?: number | null
  currency?: string
  status?: string
  effectiveAt?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
  // V5.5 峰谷定价策略字段
  /** 定价类型：FLAT(固定定价), PEAK_OFF_PEAK(峰谷定价) */
  pricingType?: string
  /** 高峰时段价格系数 (如 1.0) */
  peakMultiplier?: number
  /** 低谷时段价格系数 (如 0.50 表示 5 折) */
  offPeakMultiplier?: number
  /** 低谷开始时间 (如 "22:00:00") */
  offPeakStart?: string
  /** 低谷结束时间 (如 "08:00:00"，支持跨天) */
  offPeakEnd?: string
}

export interface VendorBill {
  id?: number
  billDate?: string
  provider?: string
  model?: string
  providerTokens?: number
  providerCost?: number
  currency?: string
  status?: string
  remark?: string
  createdAt?: string
  updatedAt?: string
}

export interface ReconcileTask {
  id?: number
  taskCode?: string
  billDate?: string
  provider?: string
  status?: string
  totalItems?: number
  diffItems?: number
  disputeItems?: number
  avgDiffRate?: number
  executedAt?: string
  remark?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface ReconcileItem {
  id?: number
  taskId?: number
  billDate?: string
  provider?: string
  model?: string
  teamCode?: string
  ourTokens?: number
  providerTokens?: number
  tokenDiff?: number
  tokenDiffRate?: number
  ourCost?: number
  providerCost?: number
  costDiff?: number
  costDiffRate?: number
  status?: string
  remark?: string
  createdAt?: string
  updatedAt?: string
}

export interface ReconcileStats {
  monthTasks: number
  diffItems: number
  disputeItems: number
  avgDiffRate: number
}

// ---- 模型价格 ----
export function listModelPrices(params?: {
  page?: number
  size?: number
  provider?: string
  model?: string
  status?: string
}): Promise<PageResult<ModelPrice>> {
  return httpGet<PageResult<ModelPrice>>('/v1/admin/model-prices', params)
}

export function getModelPrice(id: number): Promise<ModelPrice> {
  return httpGet<ModelPrice>(`/v1/admin/model-prices/${id}`)
}

export function createModelPrice(data: ModelPrice): Promise<ModelPrice> {
  return httpPost<ModelPrice>('/v1/admin/model-prices', data)
}

export function updateModelPrice(id: number, data: ModelPrice): Promise<ModelPrice> {
  return httpPut<ModelPrice>(`/v1/admin/model-prices/${id}`, data)
}

export function deleteModelPrice(id: number): Promise<void> {
  return httpDelete<void>(`/v1/admin/model-prices/${id}`)
}

export function changeModelPriceStatus(id: number, status: string): Promise<void> {
  return httpPut<void>(`/v1/admin/model-prices/${id}/status`, undefined, { status })
}

// ---- 供应商账单 ----
export function listVendorBills(params?: {
  page?: number
  size?: number
  billDate?: string
  provider?: string
  model?: string
  status?: string
}): Promise<PageResult<VendorBill>> {
  return httpGet<PageResult<VendorBill>>('/v1/admin/vendor-bills', params)
}

export function getVendorBill(id: number): Promise<VendorBill> {
  return httpGet<VendorBill>(`/v1/admin/vendor-bills/${id}`)
}

export function createVendorBill(data: VendorBill): Promise<VendorBill> {
  return httpPost<VendorBill>('/v1/admin/vendor-bills', data)
}

export function batchCreateVendorBills(data: VendorBill[]): Promise<number> {
  return httpPost<number>('/v1/admin/vendor-bills/batch', data)
}

export function updateVendorBill(id: number, data: VendorBill): Promise<VendorBill> {
  return httpPut<VendorBill>(`/v1/admin/vendor-bills/${id}`, data)
}

export function deleteVendorBill(id: number): Promise<void> {
  return httpDelete<void>(`/v1/admin/vendor-bills/${id}`)
}

// ---- 对账任务 ----
export function listReconcileTasks(params?: {
  page?: number
  size?: number
  billDate?: string
  provider?: string
  status?: string
}): Promise<PageResult<ReconcileTask>> {
  return httpGet<PageResult<ReconcileTask>>('/v1/admin/reconciles', params)
}

export function getReconcileStats(): Promise<ReconcileStats> {
  return httpGet<ReconcileStats>('/v1/admin/reconciles/stats')
}

export function getReconcileTask(id: number): Promise<ReconcileTask> {
  return httpGet<ReconcileTask>(`/v1/admin/reconciles/${id}`)
}

export function createReconcileTask(params: {
  billDate: string
  provider: string
  remark?: string
}): Promise<ReconcileTask> {
  return httpPost<ReconcileTask>('/v1/admin/reconciles', undefined, params)
}

export function executeReconcileTask(id: number): Promise<ReconcileTask> {
  return httpPost<ReconcileTask>(`/v1/admin/reconciles/${id}/execute`)
}

export function listReconcileItems(
  id: number,
  params?: {
    page?: number
    size?: number
    status?: string
    model?: string
  }
): Promise<PageResult<ReconcileItem>> {
  return httpGet<PageResult<ReconcileItem>>(`/v1/admin/reconciles/${id}/items`, params)
}

export function changeReconcileItemStatus(id: number, status: string, remark?: string): Promise<ReconcileItem> {
  return httpPut<ReconcileItem>(`/v1/admin/reconciles/items/${id}/status`, undefined, { status, remark })
}

export function deleteReconcileTask(id: number): Promise<void> {
  return httpDelete<void>(`/v1/admin/reconciles/${id}`)
}
