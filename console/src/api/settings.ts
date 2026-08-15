import { httpGet, httpPost } from '@/utils/request'

export type SettingsMap = Record<string, string>

export function getSettings(): Promise<SettingsMap> {
  return httpGet<SettingsMap>('/v1/admin/settings')
}

export function saveSettings(values: SettingsMap): Promise<SettingsMap> {
  return httpPost<SettingsMap>('/v1/admin/settings', values)
}
