// Типы и вызовы /api claudeproxy (см. StatsController и KeyController бэкенда).

export interface UsageTotals {
  requests: number
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
  /** Кэш-чтения + вырезанное инструментами экономии. */
  savedTokens: number
  /** Сэкономлено кэшем повторяющихся запросов. */
  savedByRequestCache: number
  /** Сэкономлено кэш-чтениями промпта. */
  savedByPromptCache: number
  /** Сэкономлено обрезкой истории и удалением картинок. */
  savedByTrimming: number
}

export interface WindowSummary {
  startedAtMilliseconds: number
  endsAtMilliseconds: number
  totals: UsageTotals
  /** Провайдеры, обслужившие запросы этого окна ключа. */
  providers: string[]
  /** Стоимость токенов окна по тарификации провайдеров; null — цен нет. */
  costUsd: number | null
}

export interface ProviderWindowSummary {
  providerName: string
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
  allowedModels: string[]
  limitWindowTokens: number | null
  limitMonthTokens: number | null
  createdAt: number
  lastUsedAt: number | null
  revokedAt: number | null
}

export interface KeyRequest {
  name: string
  allowedModels?: string[]
  limitWindowTokens?: number | null
  limitMonthTokens?: number | null
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
  pricingMode: string
  pricePerMillionTokens: number | null
  priceMonthly: number | null
  /** Прокси-эндпоинт провайдера (M31); null — прямое соединение. */
  proxyName: string | null
  authType: 'api_key' | 'oauth'
  oauthGrant: 'client_credentials' | 'refresh_token'
  oauthClientId: string
  oauthTokenUrl: string
  oauthScopes: string
  models: ManagedModel[]
  createdAt: number
  updatedAt: number
}

/** Прокси/туннель доступа к провайдерам (M31). */
export interface ProxyEndpoint {
  id: number
  name: string
  /** HTTP | HTTPS | SOCKS4 | SOCKS5. */
  type: string
  host: string
  port: number
  username: string | null
  hasPassword: boolean
  enabled: boolean
  lastCheckStatus: string | null
  lastCheckAt: number | null
  providerNames: string[]
  createdAt: number
  updatedAt: number
}

/** Запрос создания/обновления прокси; пароль пусто = не менять. */
export interface ProxyRequest {
  name?: string
  type?: string
  host?: string
  port?: number
  username?: string
  password?: string
  enabled?: boolean
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
  pricingMode?: string
  pricePerMillionTokens?: number | null
  priceMonthly?: number | null
  proxyName?: string | null
}

export interface ProviderCost {
  providerName: string
  pricingMode: string
  pricePerMillionTokens: number | null
  pricePerMillionDerived: boolean
  priceMonthly: number | null
  priceMonthlyDerived: boolean
  spentTokens7Days: number
  spentTokens30Days: number
}

/** Каталог оверрайдов — зеркалит ProviderSettingCatalog бэкенда. */
export interface SettingDefinition {
  key: string
  title: string
  description: string
  valueType: 'LONG' | 'DOUBLE' | 'EFFORT_LEVEL' | 'BOOLEAN' | 'NON_EMPTY_TEXT' | 'ONE_OF'
  placeholder: string
  /** Допустимые значения для настроек с выбором из списка (valueType ONE_OF). */
  values?: string[]
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
  { key: 'REQUEST_CACHE_TTL_MS', title: 'Экономия: кэш повторов, TTL (мс)', description: 'Точный повтор запроса внутри окна отдаётся из кэша бесплатно; 0 — выключить кэш для провайдера', valueType: 'LONG', placeholder: '600000' },
  { key: 'CONVERT_SYSTEM_MESSAGES_TO_USER', title: 'Совместимость: system → user внутри messages', description: 'Для локальных движков (LM Studio/Qwen): роль system внутри messages конвертируется в user', valueType: 'BOOLEAN', placeholder: 'true' },
  { key: 'LIMIT_WEEK_MODE', title: 'Режим недельного лимита', description: 'SLIDING — скользящие 7 суток; FIXED_DAY — календарная неделя со сбросом в день из настройки «День начала недели»', valueType: 'ONE_OF', placeholder: 'SLIDING', values: ['SLIDING', 'FIXED_DAY'] },
  { key: 'LIMIT_WEEK_START_DAY', title: 'День начала недели', description: 'День недели, с которого начинается календарная неделя; применяется только в режиме FIXED_DAY', valueType: 'ONE_OF', placeholder: 'MONDAY', values: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] },
  { key: 'LIMIT_MONTH_START_DAY', title: 'День начала платёжного периода', description: 'День месяца, с которого начинается 30-дневный период месячного лимита; в коротких месяцах ограничивается последним днём месяца; без настройки — скользящие 30 суток', valueType: 'ONE_OF', placeholder: '1', values: Array.from({ length: 31 }, (_, index) => String(index + 1)) },
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
  /** false — фиксированный период по настройкам провайдера (не скользящее окно). */
  sliding?: boolean
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

/** 5-часовое окно провайдера — дорожки на графике дня. */
export interface ProviderWindowBoundary {
  providerName: string
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

export interface ProviderHealth {
  providerName: string
  requests: number
  failedAttempts: number
  p50DurationMilliseconds: number
  p95DurationMilliseconds: number
}

export interface FailedAttempt {
  /** Идентификатор события usage_event — ключ запроса полной расшифровки. */
  id: number
  timestamp: number
  model: string
  providerName: string
  status: number
  error: string
}

export interface ErrorDetail {
  eventId: number
  errorDetail: string
}

export interface FallbackReport {
  providers: ProviderHealth[]
  recentFailures: FailedAttempt[]
}

export interface RouteCooldown {
  providerName: string
  cooldownUntilMilliseconds: number
  reason: string
}

export interface LatencyPointView {
  bucketStartMilliseconds: number
  ttftMeanMilliseconds: number | null
  ttftPercentile95Milliseconds: number | null
  upstreamMeanMilliseconds: number | null
  upstreamPercentile95Milliseconds: number | null
  durationMeanMilliseconds: number | null
  durationPercentile95Milliseconds: number | null
  requests: number
}

export interface LatencyStatisticsView {
  points: LatencyPointView[]
  ttftMeanMilliseconds: number | null
  ttftPercentile95Milliseconds: number | null
  upstreamMeanMilliseconds: number | null
  upstreamPercentile95Milliseconds: number | null
  durationMeanMilliseconds: number | null
  durationPercentile95Milliseconds: number | null
  requests: number
}

export interface ServerEventView {
  id: number
  timestamp: string
  /** INFO | WARN | ERROR */
  level: string
  logger: string
  message: string
  stackTrace: string | null
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

/** Счётчики диагностики кэша повторов по одной публичной модели. */
export interface RequestCacheModelStats {
  model: string
  lookups: number
  hits: number
  misses: number
  missesNoEntry: number
  missesExpired: number
  stored: number
}

/** Диагностика кэша повторов (счётчики с момента старта сервера). */
export interface RequestCacheStats {
  lookups: number
  hits: number
  misses: number
  /** Промах: записи под такой ключ нет вовсе. */
  missesNoEntry: number
  /** Промах: запись есть, но срок её жизни истёк. */
  missesExpired: number
  /** Записано новых ответов. */
  stored: number
  /** Те же счётчики в разрезе публичных моделей (по убыванию обращений). */
  perModel: RequestCacheModelStats[]
}

/** Настройка модели-оптимизатора (M30): сжатие старых tool_result. */
export interface OptimizerConfigView {
  enabled: boolean
  providerName: string | null
  model: string | null
}

/** Диагностика модели-оптимизатора (счётчики с момента старта сервера). */
export interface OptimizerStatsView {
  enabled: boolean
  providerName: string | null
  model: string | null
  /** CLOSED / OPEN / PROBE / OFF. */
  circuitState: string
  /** Блоков предложено сжатию за всё время. */
  requests: number
  cacheHits: number
  /** Успешно сжато блоков. */
  compressions: number
  /** «Несжимаемо» (ответ модели короче 80% исходника не считается). */
  notCompressed: number
  /** Блоков ушло маркером из-за отказов/таймаутов/бюджета. */
  fallbacks: number
  failures: number
  charactersBefore: number
  charactersAfter: number
  /** Оценка сэкономленных токенов: (символы до − после) / 4. */
  estimatedTokensSaved: number
  /** Токены, потраченные самой моделью-оптимизатором. */
  modelTokensSpent: number
  averageLatencyMilliseconds: number
  maxLatencyMilliseconds: number
  lastError: string | null
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
  providerWindowHistory: (limit = 20) =>
    getJson<ProviderWindowSummary[]>(`/api/provider-windows?limit=${limit}`),
  timeline: (bucket: 'hour' | 'day', key: string | null) =>
    getJson<TimelinePoint[]>(
      `/api/timeline?bucket=${bucket}${key ? `&key=${encodeURIComponent(key)}` : ''}`,
    ),
  timelineRange: (bucket: 'hour' | 'day', key: string | null, from: number, to: number) =>
    getJson<TimelinePoint[]>(
      `/api/timeline?bucket=${bucket}&from=${from}&to=${to}` +
        `${key ? `&key=${encodeURIComponent(key)}` : ''}`,
    ),
  latency: (bucket: 'hour' | 'day', from: number, to: number) =>
    getJson<LatencyStatisticsView>(`/api/latency?bucket=${bucket}&from=${from}&to=${to}`),
  requestCacheStats: () => getJson<RequestCacheStats>('/api/request-cache-stats'),
  optimizerStats: () => getJson<OptimizerStatsView>('/api/optimizer/stats'),
  optimizerConfig: () => getJson<OptimizerConfigView>('/api/optimizer/config'),
  updateOptimizerConfig: (request: {
    enabled: boolean
    providerName: string | null
    model: string | null
  }) => putJson<OptimizerConfigView>('/api/optimizer/config', request),
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
  providerWindowBoundaries: (from: number, to: number) =>
    getJson<ProviderWindowBoundary[]>(
      `/api/provider-window-boundaries?from=${from}&to=${to}`,
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
  listProxies: () => getJson<ProxyEndpoint[]>('/api/proxies'),
  createProxy: (request: ProxyRequest) => postJson<ProxyEndpoint>('/api/proxies', request),
  updateProxy: (id: number, request: ProxyRequest) =>
    putJson<ProxyEndpoint>(`/api/proxies/${id}`, request),
  deleteProxy: (id: number) => deleteRequest(`/api/proxies/${id}`),
  checkProxy: (id: number, testUrl?: string) =>
    postJson<ProxyEndpoint>(`/api/proxies/${id}/check`, testUrl ? { testUrl } : {}),
  createKey: (request: KeyRequest) => postJson<CreatedKey>('/api/keys', request),
  updateKey: (id: number, request: KeyRequest) =>
    putJson<ClientKey>(`/api/keys/${id}`, request),
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
  fallbackReport: (range: string, key: string | null) =>
    getJson<FallbackReport>(
      `/api/fallback-report?range=${range}${key ? `&key=${encodeURIComponent(key)}` : ''}`,
    ),
  errorDetail: (eventId: number) => getJson<ErrorDetail>(`/api/stats/errors/${eventId}`),
  routeCooldowns: () => getJson<RouteCooldown[]>('/api/route-cooldowns'),
  providerCosts: () => getJson<ProviderCost[]>('/api/provider-costs'),
  serverEvents: (params: { level?: string; loggerContains?: string; messageContains?: string; beforeId?: number; limit?: number } = {}) => {
    const query = new URLSearchParams()
    if (params.level) query.set('level', params.level)
    if (params.loggerContains) query.set('loggerContains', params.loggerContains)
    if (params.messageContains) query.set('messageContains', params.messageContains)
    if (params.beforeId !== undefined) query.set('beforeId', String(params.beforeId))
    if (params.limit !== undefined) query.set('limit', String(params.limit))
    const suffix = Array.from(query.keys()).length > 0 ? `?${query.toString()}` : ''
    return getJson<ServerEventView[]>(`/api/server-events${suffix}`)
  },
  clearServerEvents: () => deleteRequest('/api/server-events'),
}
