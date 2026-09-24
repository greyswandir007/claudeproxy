// Типы и вызовы /api claudeproxy (см. StatsController и KeyController бэкенда).

export interface UsageTotals {
  requests: number
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
}

export interface WindowSummary {
  startedAtMilliseconds: number
  endsAtMilliseconds: number
  totals: UsageTotals
}

export interface RangeSummary {
  range: string
  fromMilliseconds: number
  toMilliseconds: number
  totals: UsageTotals
  window: WindowSummary | null
}

export interface GroupedUsage {
  label: string
  requests: number
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
}

export interface TimelinePoint {
  bucketStartMilliseconds: number
  requests: number
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
}

export interface ClientKey {
  id: number
  name: string
  keyPrefix: string
  createdAt: number
  lastUsedAt: number | null
  revokedAt: number | null
}

export interface CreatedKey {
  clientKey: ClientKey
  fullKey: string
}

export interface ProviderConfig {
  name: string
  type: string
  baseUrl: string
  models: { public: string; upstream: string }[]
}

export interface ProxyConfig {
  windowHours: number
  exposedModels: string[]
  providers: ProviderConfig[]
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path)
  if (!response.ok) {
    throw new Error(`GET ${path} → HTTP ${response.status}`)
  }
  return response.json() as Promise<T>
}

async function postJson<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) {
    const errorText = await response.text().catch(() => '')
    throw new Error(`POST ${path} → HTTP ${response.status} ${errorText}`)
  }
  return response.json() as Promise<T>
}

export const api = {
  summary: (range: string, key: string | null) =>
    getJson<RangeSummary>(`/api/summary?range=${range}${key ? `&key=${encodeURIComponent(key)}` : ''}`),
  byModel: (range: string, key: string | null) =>
    getJson<GroupedUsage[]>(`/api/by-model?range=${range}${key ? `&key=${encodeURIComponent(key)}` : ''}`),
  byProvider: (range: string, key: string | null) =>
    getJson<GroupedUsage[]>(`/api/by-provider?range=${range}${key ? `&key=${encodeURIComponent(key)}` : ''}`),
  currentWindow: (key: string) =>
    getJson<WindowSummary | null>(`/api/window?key=${encodeURIComponent(key)}`),
  windowHistory: (key: string, limit = 20) =>
    getJson<WindowSummary[]>(`/api/windows?key=${encodeURIComponent(key)}&limit=${limit}`),
  timeline: (bucket: 'hour' | 'day') => getJson<TimelinePoint[]>(`/api/timeline?bucket=${bucket}`),
  config: () => getJson<ProxyConfig>('/api/config'),
  keys: () => getJson<ClientKey[]>('/api/keys'),
  createKey: (name: string) => postJson<CreatedKey>('/api/keys', { name }),
  revokeKey: (id: number) => postJson<{ revoked: boolean }>(`/api/keys/${id}/revoke`),
}
