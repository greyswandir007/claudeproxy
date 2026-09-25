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
  // 204/пустое тело (например, /api/window без активного окна) → null
  const responseText = await response.text()
  return (responseText.length === 0 ? null : JSON.parse(responseText)) as T
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

async function putJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    const errorText = await response.text().catch(() => '')
    throw new Error(`PUT ${path} → HTTP ${response.status} ${errorText}`)
  }
  return response.json() as Promise<T>
}

async function deleteRequest(path: string): Promise<void> {
  const response = await fetch(path, { method: 'DELETE' })
  if (!response.ok) {
    throw new Error(`DELETE ${path} → HTTP ${response.status}`)
  }
}

export interface ManagedModel {
  id: number
  providerId: number
  publicName: string
  upstreamName: string
  reasoning: string
  maxCompletionParam: boolean
  priority: number
  exposed: boolean
}

export interface ManagedProvider {
  id: number
  name: string
  type: string
  baseUrl: string
  apiKeyPreview: string
  extraHeaders: Record<string, string>
  exposed: boolean
  limitWindowTokens: number | null
  limitWeekTokens: number | null
  limitMonthTokens: number | null
  models: ManagedModel[]
  createdAt: number
  updatedAt: number
}

export interface ProviderRequest {
  name: string
  type: string
  baseUrl: string
  apiKey?: string
  extraHeaders?: Record<string, string>
  exposed?: boolean
  limitWindowTokens?: number | null
  limitWeekTokens?: number | null
  limitMonthTokens?: number | null
}

export interface ModelTokens {
  modelName: string
  tokens: number
}

export interface LimitPeriodUsage {
  limitTokens: number
  spentTokens: number
  fromMilliseconds: number
  toMilliseconds: number
  modelTokens: ModelTokens[]
  /** true — лимит выведен из другой категории (в БД не хранится). */
  derived: boolean
}

export interface ProviderLimitUsage {
  providerName: string
  window: LimitPeriodUsage | null
  week: LimitPeriodUsage | null
  month: LimitPeriodUsage | null
  /** true — окно активно; false — показано последнее истекшее. */
  windowActive: boolean
}

export interface ModelRequest {
  publicName: string
  upstreamName: string
  reasoning: string
  maxCompletionParam: boolean
  priority: number
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
  timeline: (bucket: 'hour' | 'day', key: string | null) =>
    getJson<TimelinePoint[]>(
      `/api/timeline?bucket=${bucket}${key ? `&key=${encodeURIComponent(key)}` : ''}`,
    ),
  config: () => getJson<ProxyConfig>('/api/config'),
  keys: () => getJson<ClientKey[]>('/api/keys'),
  createKey: (name: string) => postJson<CreatedKey>('/api/keys', { name }),
  revokeKey: (id: number) => postJson<{ revoked: boolean }>(`/api/keys/${id}/revoke`),
  listProviders: () => getJson<ManagedProvider[]>('/api/providers'),
  createProvider: (request: ProviderRequest) => postJson<ManagedProvider>('/api/providers', request),
  updateProvider: (id: number, request: ProviderRequest) =>
    putJson<ManagedProvider>(`/api/providers/${id}`, request),
  deleteProvider: (id: number) => deleteRequest(`/api/providers/${id}`),
  createModel: (providerId: number, request: ModelRequest) =>
    postJson<ManagedModel>(`/api/providers/${providerId}/models`, request),
  updateModel: (id: number, request: ModelRequest) =>
    putJson<ManagedModel>(`/api/models/${id}`, request),
  deleteModel: (id: number) => deleteRequest(`/api/models/${id}`),
  discoverModels: (request: { type: string; baseUrl: string; apiKey?: string; providerId?: number }) =>
    postJson<{ models: string[] }>('/api/providers/discover-models', request),
  setProviderExposed: (id: number, exposed: boolean) =>
    putJson<{ exposed: boolean }>(`/api/providers/${id}/exposure`, { exposed }),
  setModelExposed: (id: number, exposed: boolean) =>
    putJson<{ exposed: boolean }>(`/api/models/${id}/exposure`, { exposed }),
  providerLimitUsage: () => getJson<ProviderLimitUsage[]>('/api/provider-limits'),
}
