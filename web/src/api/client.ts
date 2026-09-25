// Типы и вызовы /api claudeproxy (см. StatsController и KeyController бэкенда).

export interface UsageTotals {
  requests: number
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
  /** Кэш-чтения + вырезанное инструментами экономии. */
  savedTokens: number
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
  effortMapping: Record<string, string>
  settingOverrides: Record<string, string>
  authType: 'api_key' | 'oauth'
  oauthGrant: 'client_credentials' | 'refresh_token'
  oauthClientId: string
  oauthTokenUrl: string
  oauthScopes: string
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
  effortMapping?: Record<string, string>
  settingOverrides?: Record<string, string>
  authType?: 'api_key' | 'oauth'
  oauthGrant?: 'client_credentials' | 'refresh_token'
  oauthClientId?: string
  oauthClientSecret?: string
  oauthTokenUrl?: string
  oauthScopes?: string
  oauthRefreshToken?: string
}

/** Каталог оверрайдов — зеркалит ProviderSettingCatalog бэкенда. */
export interface SettingDefinition {
  key: string
  title: string
  description: string
  valueType: 'LONG' | 'DOUBLE' | 'EFFORT_LEVEL' | 'BOOLEAN' | 'NON_EMPTY_TEXT'
  placeholder: string
}

export const SETTING_CATALOG: SettingDefinition[] = [
  { key: 'API_TIMEOUT_MS', title: 'Таймаут вызова провайдера, мс', description: 'Молчание провайдера дольше — обрыв и переключение на следующий маршрут', valueType: 'LONG', placeholder: '120000' },
  { key: 'MAX_OUTPUT_TOKENS', title: 'Потолок max_tokens', description: 'max_tokens запроса ужимается до этого значения', valueType: 'LONG', placeholder: '8192' },
  { key: 'MAX_INPUT_TOKENS', title: 'Лимит входных токенов', description: 'Оценка входа выше — вежливый 413', valueType: 'LONG', placeholder: '200000' },
  { key: 'TEMPERATURE_OVERRIDE', title: 'Температура (переопределение)', description: 'temperature заменяется этим значением', valueType: 'DOUBLE', placeholder: '0.2' },
  { key: 'TOP_P_OVERRIDE', title: 'top_p (переопределение)', description: 'top_p заменяется этим значением', valueType: 'DOUBLE', placeholder: '0.9' },
  { key: 'FORCED_REASONING_EFFORT', title: 'Принудительный effort', description: 'Уровень effort после маппера', valueType: 'EFFORT_LEVEL', placeholder: 'medium' },
  { key: 'DISABLE_THINKING', title: 'Выключить thinking', description: 'Запрос уходит с thinking: disabled', valueType: 'BOOLEAN', placeholder: 'true' },
  { key: 'EXTRA_STOP_SEQUENCE', title: 'Доп. stop-последовательность', description: 'Добавляется к stop_sequences', valueType: 'NON_EMPTY_TEXT', placeholder: '</end>' },
  { key: 'CACHE_INJECTION', title: 'Экономия: инъекция кэш-маркеров', description: 'anthropic: cache_control на system/tools; openai: стабильный prompt_cache_key', valueType: 'BOOLEAN', placeholder: 'true' },
  { key: 'TRIM_OLD_TOOL_RESULTS', title: 'Экономия: обрезка старых tool_result', description: 'Старше последних 4 → «[trimmed]»', valueType: 'BOOLEAN', placeholder: 'true' },
  { key: 'DROP_OLD_TOOL_IMAGES', title: 'Экономия: удаление старых картинок', description: 'Старше последних 2 сообщений (~1600 токенов/шт)', valueType: 'BOOLEAN', placeholder: 'true' },
]

export const EFFORT_LEVELS = ['low', 'medium', 'high', 'xhigh', 'max'] as const

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

export interface GroupedTimelinePoint {
  bucketStartMilliseconds: number
  label: string
  tokens: number
  requests: number
}

export interface WindowBoundary {
  startedAtMilliseconds: number
  endsAtMilliseconds: number
}

export interface ChatMessage {
  id: number
  role: string
  content: string
  createdAt: number
}

export interface ChatState {
  thread: { clientKey: string; title: string; updatedAt: number }
  messages: ChatMessage[]
}

export const chatApi = {
  state: (key: string) => getJson<ChatState>(`/api/chat/state?key=${encodeURIComponent(key)}`),
  renameThread: (key: string, title: string) =>
    putJson<{ title: string }>(
      `/api/chat/thread?key=${encodeURIComponent(key)}`,
      { title },
    ),
  clear: (key: string) => deleteRequest(`/api/chat/messages?key=${encodeURIComponent(key)}`),

  /** Отправка со стримингом: onChunk получает каждую порцию текста ассистента. */
  send: async (
    key: string,
    model: string,
    content: string,
    onChunk: (text: string) => void,
    onDone: (stopReason: string) => void,
    onError: (message: string) => void,
  ): Promise<void> => {
    const response = await fetch(
      `/api/chat/send?key=${encodeURIComponent(key)}&model=${encodeURIComponent(model)}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content }),
      },
    )
    if (!response.ok || !response.body) {
      const errorText = await response.text().catch(() => '')
      onError(`HTTP ${response.status} ${errorText.slice(0, 200)}`)
      return
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let newlineIndex: number
      while ((newlineIndex = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newlineIndex).trim()
        buffer = buffer.slice(newlineIndex + 1)
        if (line.length === 0) continue
        const event = JSON.parse(line)
        if (event.type === 'text') onChunk(event.text as string)
        else if (event.type === 'done') onDone(event.stop_reason as string)
        else if (event.type === 'error') onError(event.message as string)
      }
    }
  },
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
  byModel: (range: string, key: string | null, from?: number, to?: number) =>
    getJson<GroupedUsage[]>(
      `/api/by-model?range=${range}` +
        `${from !== undefined && to !== undefined ? `&from=${from}&to=${to}` : ''}` +
        `${key ? `&key=${encodeURIComponent(key)}` : ''}`,
    ),
  byProvider: (range: string, key: string | null, from?: number, to?: number) =>
    getJson<GroupedUsage[]>(
      `/api/by-provider?range=${range}` +
        `${from !== undefined && to !== undefined ? `&from=${from}&to=${to}` : ''}` +
        `${key ? `&key=${encodeURIComponent(key)}` : ''}`,
    ),
  currentWindow: (key: string) =>
    getJson<WindowSummary | null>(`/api/window?key=${encodeURIComponent(key)}`),
  windowHistory: (key: string, limit = 20) =>
    getJson<WindowSummary[]>(`/api/windows?key=${encodeURIComponent(key)}&limit=${limit}`),
  timeline: (bucket: 'hour' | 'day', key: string | null) =>
    getJson<TimelinePoint[]>(
      `/api/timeline?bucket=${bucket}${key ? `&key=${encodeURIComponent(key)}` : ''}`,
    ),
  timelineRange: (bucket: 'hour' | 'day', key: string | null, from: number, to: number) =>
    getJson<TimelinePoint[]>(
      `/api/timeline?bucket=${bucket}&from=${from}&to=${to}` +
        `${key ? `&key=${encodeURIComponent(key)}` : ''}`,
    ),
  groupedTimeline: (
    bucket: 'hour' | 'day',
    key: string | null,
    from: number,
    to: number,
    group: 'model' | 'provider',
  ) =>
    getJson<GroupedTimelinePoint[]>(
      `/api/timeline?bucket=${bucket}&from=${from}&to=${to}&group=${group}` +
        `${key ? `&key=${encodeURIComponent(key)}` : ''}`,
    ),
  windowBoundaries: (key: string, from: number, to: number) =>
    getJson<WindowBoundary[]>(
      `/api/window-boundaries?key=${encodeURIComponent(key)}&from=${from}&to=${to}`,
    ),
  byModelRange: (key: string | null, from: number, to: number) =>
    getJson<GroupedUsage[]>(
      `/api/by-model?from=${from}&to=${to}${key ? `&key=${encodeURIComponent(key)}` : ''}`,
    ),
  byProviderRange: (key: string | null, from: number, to: number) =>
    getJson<GroupedUsage[]>(
      `/api/by-provider?from=${from}&to=${to}${key ? `&key=${encodeURIComponent(key)}` : ''}`,
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
