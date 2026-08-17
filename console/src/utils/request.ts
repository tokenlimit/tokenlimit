import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

export interface ApiResult<T = unknown> {
  success: boolean
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  page: number
  size: number
  total: number
  records: T[]
}

const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截器
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：直接返回 data 载荷
request.interceptors.response.use(
  (response) => {
    const res = response.data as ApiResult
    if (!res.success || res.code !== 0) {
      // 业务层未授权（会话被踢/过期等）：清除本地登录态并回登录页
      if (res.code === 401 || res.code === 4001) {
        clearAuthAndRedirect()
      }
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res.data as never
  },
  (error) => {
    const status = error.response?.status
    // HTTP 401：Spring Security 统一返回，会话缺失/失效
    if (status === 401) {
      clearAuthAndRedirect()
      ElMessage.error('登录已过期，请重新登录')
    } else if (status === 403) {
      // 优先透传后端消息（如"首次登录必须修改密码"）
      ElMessage.error(error.response?.data?.message || '无权访问该资源')
    } else {
      const msg = error.response?.data?.message || error.message || '网络错误'
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

/** 清除本地登录态并跳转登录页（避免与 user store 循环依赖，直接操作 localStorage + location） */
function clearAuthAndRedirect() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('userName')
  localStorage.removeItem('role')
  localStorage.removeItem('namespaceCode')
  localStorage.removeItem('teamCode')
  localStorage.removeItem('userCode')
  localStorage.removeItem('mustChangePassword')
  if (!window.location.pathname.startsWith('/login')) {
    window.location.href = '/login'
  }
}

/** 泛型请求封装，返回后端 data 载荷 */
async function http<T>(config: AxiosRequestConfig): Promise<T> {
  return request(config) as Promise<T>
}

export const httpGet = <T>(url: string, params?: Record<string, unknown>) =>
  http<T>({ method: 'get', url, params })

export const httpPost = <T>(url: string, data?: unknown, params?: Record<string, unknown>) =>
  http<T>({ method: 'post', url, data, params })

export const httpPut = <T>(url: string, data?: unknown, params?: Record<string, unknown>) =>
  http<T>({ method: 'put', url, data, params })

export const httpDelete = <T>(url: string, params?: Record<string, unknown>) =>
  http<T>({ method: 'delete', url, params })

export default request
