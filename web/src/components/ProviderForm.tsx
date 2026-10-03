import { useEffect, useState } from 'react'
import {
  api,
  EFFORT_LEVELS,
  SETTING_CATALOG,
  type ManagedProvider,
} from '../api/client'
import { FieldHint } from './FieldHint'
import KeyValueRows from './KeyValueRows'

// Форма провайдера. Базовый набор: имя, тип, base-url, api-ключ.
// Расширенный (extra-заголовки): при создании свёрнут, при редактировании — сразу развёрнут.
// «Загрузить модели провайдера» → чек-лист найденных моделей (выбрать все / снять
// выделение, публичное имя для каждой); выбранные добавляются при сохранении.
/** Токены → строка в миллионах, до 2 знаков («5,5» = 5 500 000). */
const millionsOf = (tokens: number): number => Math.round((tokens / 1_000_000) * 100) / 100

/** Ввод в миллионах → токены; пустое/неположительное — лимит не задан (null). */
const millionsToTokens = (value: string): number | null => {
  const parsed = Number(value.replace(',', '.'))
  return Number.isFinite(parsed) && parsed > 0 ? Math.round(parsed * 1_000_000) : null
}

/** Ключи каталога оверрайдов, доступные для добавления (добавленные скрыты). */
function availableSettingKeys(settingOverrides: Record<string, string>): string[] {
  return SETTING_CATALOG.map((definition) => definition.key).filter(
    (key) => !(key in settingOverrides),
  )
}

/** Расчётная вторая цена: per_million ↔ monthly из месячного лимита (млн → токены). */
function derivedPriceLabel(
  pricingMode: 'none' | 'per_million' | 'monthly',
  pricePerMillionTokens: string,
  priceMonthly: string,
  monthTokensMillionsInput: string,
): string | null {
  const monthTokens = millionsToTokens(monthTokensMillionsInput)
  if (monthTokens == null) return null
  if (pricingMode === 'per_million') {
    const price = Number(pricePerMillionTokens.replace(',', '.'))
    if (!Number.isFinite(price) || price <= 0) return null
    return `≈ $${(price * (monthTokens / 1_000_000)).toFixed(2)}/мес из месячного лимита`
  }
  if (pricingMode === 'monthly') {
    const price = Number(priceMonthly.replace(',', '.'))
    if (!Number.isFinite(price) || price <= 0) return null
    return `≈ $${(price / (monthTokens / 1_000_000)).toFixed(2)}/1М токенов из месячного лимита`
  }
  return null
}

export default function ProviderForm({
  provider,
  onDiscovered,
  onSaved,
  onCancel,
}: {
  provider?: ManagedProvider
  onDiscovered?: (models: string[]) => void
  onSaved: () => void
  onCancel: () => void
}) {
  const isEditMode = provider !== undefined
  const [name, setName] = useState(provider?.name ?? '')
  const [type, setType] = useState(provider?.type ?? 'openai')
  const [baseUrl, setBaseUrl] = useState(provider?.baseUrl ?? '')
  const [proxyName, setProxyName] = useState(provider?.proxyName ?? '')
  const [availableProxies, setAvailableProxies] = useState<string[]>([])
  const [apiKey, setApiKey] = useState('')
  const [extraHeaderRows, setExtraHeaderRows] = useState(
    Object.entries(provider?.extraHeaders ?? {}).map(([key, value]) => ({ key, value })),
  )
  const [limitWindowTokens, setLimitWindowTokens] = useState(
    provider?.limitWindowTokens != null ? String(millionsOf(provider.limitWindowTokens)) : '',
  )
  const [limitWeekTokens, setLimitWeekTokens] = useState(
    provider?.limitWeekTokens != null ? String(millionsOf(provider.limitWeekTokens)) : '',
  )
  const [limitMonthTokens, setLimitMonthTokens] = useState(
    provider?.limitMonthTokens != null ? String(millionsOf(provider.limitMonthTokens)) : '',
  )
  const [pricingMode, setPricingMode] = useState<'none' | 'per_million' | 'monthly'>(
    provider?.pricingMode === 'per_million' || provider?.pricingMode === 'monthly'
      ? (provider.pricingMode as 'per_million' | 'monthly')
      : 'none',
  )
  const [pricePerMillionTokens, setPricePerMillionTokens] = useState(
    provider?.pricePerMillionTokens != null ? String(provider.pricePerMillionTokens) : '',
  )
  const [priceMonthly, setPriceMonthly] = useState(
    provider?.priceMonthly != null ? String(provider.priceMonthly) : '',
  )
  const [effortMapperEnabled, setEffortMapperEnabled] = useState(
    Object.keys(provider?.effortMapping ?? {}).length > 0,
  )
  const [effortLevels, setEffortLevels] = useState<Record<string, string>>({
    low: provider?.effortMapping?.low ?? 'low',
    medium: provider?.effortMapping?.medium ?? 'medium',
    high: provider?.effortMapping?.high ?? 'high',
    xhigh: provider?.effortMapping?.xhigh ?? 'xhigh',
    max: provider?.effortMapping?.max ?? 'max',
  })
  const [settingOverrides, setSettingOverrides] = useState<Record<string, string>>(
    { ...(provider?.settingOverrides ?? {}) },
  )
  const [selectedSettingKey, setSelectedSettingKey] = useState('')
  const [showExtended, setShowExtended] = useState(isEditMode)

  const [authType, setAuthType] = useState<'api_key' | 'oauth'>(
    provider?.authType === 'oauth' ? 'oauth' : 'api_key',
  )
  const [oauthGrant, setOauthGrant] = useState<'client_credentials' | 'refresh_token'>(
    provider?.oauthGrant === 'refresh_token' ? 'refresh_token' : 'client_credentials',
  )
  const [oauthClientId, setOauthClientId] = useState(provider?.oauthClientId ?? '')
  const [oauthClientSecret, setOauthClientSecret] = useState('')
  const [oauthTokenUrl, setOauthTokenUrl] = useState(provider?.oauthTokenUrl ?? '')
  const [oauthScopes, setOauthScopes] = useState(provider?.oauthScopes ?? '')
  const [oauthRefreshToken, setOauthRefreshToken] = useState('')
  const [discoveredModels, setDiscoveredModels] = useState<string[]>([])
  const [hiddenAlreadyAddedCount, setHiddenAlreadyAddedCount] = useState(0)
  const [selectedDiscovered, setSelectedDiscovered] = useState<Record<string, boolean>>({})
  const [publicNames, setPublicNames] = useState<Record<string, string>>({})
  const [discovering, setDiscovering] = useState(false)

  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const existingUpstreamNames = new Set(provider?.models.map((model) => model.upstreamName) ?? [])
  const selectedCount = discoveredModels.filter((upstream) => selectedDiscovered[upstream]).length

  const discoverModels = () => {
    setDiscovering(true)
    setError(null)
    api
      .discoverModels({
        type,
        baseUrl: baseUrl.trim(),
        apiKey: apiKey.trim().length > 0 ? apiKey.trim() : undefined,
        providerId: isEditMode ? provider.id : undefined,
      })
      .then((result) => {
        setDiscovering(false)
        const alreadyAdded = result.models.filter((upstream) =>
          existingUpstreamNames.has(upstream),
        )
        const freshModels = result.models.filter(
          (upstream) => !existingUpstreamNames.has(upstream),
        )
        setHiddenAlreadyAddedCount(alreadyAdded.length)
        setDiscoveredModels(freshModels)
        // по умолчанию выбраны все новые модели
        setSelectedDiscovered(
          Object.fromEntries(freshModels.map((upstream) => [upstream, true])),
        )
        setPublicNames({})
        onDiscovered?.(result.models)
      })
      .catch((discoverError: Error) => {
        setDiscovering(false)
        setError(discoverError.message)
      })
  }

  const selectAllDiscovered = () =>
    setSelectedDiscovered(
      Object.fromEntries(discoveredModels.map((upstream) => [upstream, true])),
    )

  const clearDiscoveredSelection = () =>
    setSelectedDiscovered(
      Object.fromEntries(discoveredModels.map((upstream) => [upstream, false])),
    )

  // список прокси для дропдауна (M31)
  useEffect(() => {
    api
      .listProxies()
      .then((loaded) => setAvailableProxies(loaded.map((proxy) => proxy.name)))
      .catch(() => setAvailableProxies([]))
  }, [])

  const save = () => {
    setSaving(true)
    setError(null)
    const extraHeaders = Object.fromEntries(
      extraHeaderRows
        .filter((row) => row.key.trim().length > 0)
        .map((row) => [row.key.trim(), row.value] as [string, string]),
    )
    const request = {
      name: name.trim(),
      type,
      baseUrl: baseUrl.trim(),
      apiKey: apiKey.trim().length > 0 ? apiKey.trim() : undefined,
      extraHeaders,
      limitWindowTokens: millionsToTokens(limitWindowTokens),
      limitWeekTokens: millionsToTokens(limitWeekTokens),
      limitMonthTokens: millionsToTokens(limitMonthTokens),
      effortMapping: effortMapperEnabled
        ? Object.fromEntries(
            EFFORT_LEVELS.filter((level) => effortLevels[level]?.trim().length > 0).map(
              (level) => [level, effortLevels[level].trim()],
            ),
          )
        : {},
      settingOverrides,
      authType,
      oauthGrant,
      oauthClientId: oauthClientId.trim(),
      ...(oauthClientSecret.trim().length > 0 ? { oauthClientSecret: oauthClientSecret.trim() } : {}),
      oauthTokenUrl: oauthTokenUrl.trim(),
      oauthScopes: oauthScopes.trim(),
      ...(oauthRefreshToken.trim().length > 0 ? { oauthRefreshToken: oauthRefreshToken.trim() } : {}),
      ...(pricingMode === 'per_million'
        ? {
            pricingMode,
            pricePerMillionTokens: Number(pricePerMillionTokens.replace(',', '.')) || null,
            priceMonthly: null,
          }
        : pricingMode === 'monthly'
          ? {
              pricingMode,
              priceMonthly: Number(priceMonthly.replace(',', '.')) || null,
              pricePerMillionTokens: null,
            }
          : { pricingMode: '', pricePerMillionTokens: null, priceMonthly: null }),
      proxyName: proxyName.length > 0 ? proxyName : '',
    }
    const result = isEditMode
      ? api.updateProvider(provider.id, request)
      : api.createProvider(request)
    result
      .then(async (savedProvider) => {
        const selectedUpstreams = discoveredModels.filter(
          (upstream) => selectedDiscovered[upstream],
        )
        const failures: string[] = []
        for (const upstream of selectedUpstreams) {
          const customPublicName = (publicNames[upstream] ?? '').trim()
          try {
            await api.createModel(savedProvider.id, {
              publicName: customPublicName.length > 0 ? customPublicName : upstream,
              upstreamName: upstream,
              reasoning: 'map',
              maxCompletionParam: false,
              priority: 100,
            })
            setSelectedDiscovered((current) => ({ ...current, [upstream]: false }))
          } catch (modelError) {
            failures.push(`${upstream}: ${(modelError as Error).message}`)
          }
        }
        if (failures.length > 0) {
          setError(
            `Провайдер сохранён, но часть моделей не добавлена — ${failures.join('; ')}. ` +
              'Исправьте и сохраните снова либо закройте форму.',
          )
          setSaving(false)
          return
        }
        setSaving(false)
        onSaved()
      })
      .catch((saveError: Error) => {
        setSaving(false)
        setError(saveError.message)
      })
  }

  return (
    <div className="form-card">
      <h3>{isEditMode ? `Редактирование «${provider.name}»` : 'Новый провайдер'}</h3>
      {error && <div className="error-banner">{error}</div>}
      <div className="form-grid">
        <label>
          Имя
          <input
            type="text"
            value={name}
            placeholder="например openrouter"
            onChange={(event) => setName(event.target.value)}
          />
          <FieldHint text="Уникальное имя провайдера; фигурирует в статистике, использовании и привязке моделей." />
        </label>
        <label>
          Тип
          <select value={type} onChange={(event) => setType(event.target.value)}>
            <option value="openai">openai (перевод протокола)</option>
            <option value="anthropic">anthropic (pass-through)</option>
          </select>
          <FieldHint text="anthropic — прозрачный проход Claude API; openai — полный перевод Claude ↔ OpenAI (tool calls, стриминг)." />
        </label>
        <label className="form-wide">
          base-url
          <input
            type="text"
            value={baseUrl}
            placeholder="https://openrouter.ai/api/v1"
            onChange={(event) => setBaseUrl(event.target.value)}
          />
          <FieldHint text="Корень API провайдера без /v1 — путь эндпоинта прокси добавит сам." />
        </label>
        <label className="form-wide">
          Прокси (M31)
          <select value={proxyName} onChange={(event) => setProxyName(event.target.value)}>
            <option value="">— без прокси —</option>
            {availableProxies.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
          <FieldHint text="Все исходящие вызовы провайдера (чат, файлы, OAuth, оптимизатор) пойдут через выбранный прокси." />
        </label>
        <label className="form-wide">
          Авторизация
          <select value={authType} onChange={(event) => setAuthType(event.target.value as 'api_key' | 'oauth')}>
            <option value="api_key">api-ключ</option>
            <option value="oauth">OAuth</option>
          </select>
          <FieldHint text="OAuth — для провайдеров с client_credentials или refresh_token; токен обновляется автоматически." />
        </label>
        {authType === 'api_key' ? (
          <label className="form-wide">
            api-ключ{' '}
            {isEditMode && (
              <span className="muted">
                (текущий: {provider.apiKeyPreview || '—'}; оставьте пустым, чтобы не менять)
              </span>
            )}
            <input
              type="password"
              value={apiKey}
              placeholder={isEditMode ? 'не менять' : 'ключ или ${ENV_VAR}'}
              onChange={(event) => setApiKey(event.target.value)}
            />
            <FieldHint text="Литерал или ${ENV:ИМЯ}-ссылка; при редактировании пусто = не менять." />
          </label>
        ) : (
          <>
            <label>
              Grant
              <select
                value={oauthGrant}
                onChange={(event) =>
                  setOauthGrant(event.target.value as 'client_credentials' | 'refresh_token')
                }
              >
                <option value="client_credentials">client_credentials</option>
                <option value="refresh_token">refresh_token</option>
              </select>
              <FieldHint text="client_credentials — серверная авторизация по client_id/secret; refresh_token — доступ по пользовательскому токену с ротацией." />
            </label>
            <label>
              client_id
              <input
                type="text"
                value={oauthClientId}
                placeholder="client_id"
                onChange={(event) => setOauthClientId(event.target.value)}
              />
            </label>
            <label className="form-wide">
              client_secret{' '}
              {isEditMode && <span className="muted">(пусто = не менять)</span>}
              <input
                type="password"
                value={oauthClientSecret}
                placeholder="секрет или ${ENV_VAR}"
                onChange={(event) => setOauthClientSecret(event.target.value)}
              />
            </label>
            <label className="form-wide">
              token URL
              <input
                type="text"
                value={oauthTokenUrl}
                placeholder="https://provider.example/oauth/token"
                onChange={(event) => setOauthTokenUrl(event.target.value)}
              />
            </label>
            <label className="form-wide">
              scopes (через пробел, опционально)
              <input
                type="text"
                value={oauthScopes}
                placeholder="read write"
                onChange={(event) => setOauthScopes(event.target.value)}
              />
            </label>
            {oauthGrant === 'refresh_token' && (
              <label className="form-wide">
                refresh-токен{' '}
                {isEditMode && <span className="muted">(пусто = не менять; ротация пишется в БД)</span>}
                <input
                  type="password"
                  value={oauthRefreshToken}
                  placeholder="исходный refresh-токен или ${ENV_VAR}"
                  onChange={(event) => setOauthRefreshToken(event.target.value)}
                />
              </label>
            )}
          </>
        )}
      </div>
      {showExtended ? (
        <>
          <div className="form-grid">
            <label>
              Расширенные: лимит, млн токенов — 5 часов
              <input
                type="number"
                min={0.1}
                step={0.1}
                value={limitWindowTokens}
                placeholder="не задан (5,5 = 5,5 млн)"
                onChange={(event) => setLimitWindowTokens(event.target.value)}
              />
            </label>
            <label>
              Лимит, млн токенов — неделя (7 дней)
              <input
                type="number"
                min={0.1}
                step={0.1}
                value={limitWeekTokens}
                placeholder="не задан"
                onChange={(event) => setLimitWeekTokens(event.target.value)}
              />
            </label>
            <label>
              Лимит, млн токенов — месяц (30 дней)
              <input
                type="number"
                min={0.1}
                step={0.1}
                value={limitMonthTokens}
                placeholder="не задан"
                onChange={(event) => setLimitMonthTokens(event.target.value)}
              />
            </label>
          </div>
          <p className="muted">
            Лимиты в миллионах токенов, информационные — выработка видна на дашборде;
            можно задать один или несколько, пустое поле = не задан. Незаданная
            категория выводится из заданных (месяц → неделя → 5 часов).
          </p>
          <div className="form-grid">
            <label>
              Стоимость — режим
              <select
                value={pricingMode}
                onChange={(event) =>
                  setPricingMode(event.target.value as 'none' | 'per_million' | 'monthly')
                }
              >
                <option value="none">не задано</option>
                <option value="per_million">за 1М токенов, $</option>
                <option value="monthly">подписка, $/мес</option>
              </select>
            </label>
            {pricingMode === 'per_million' && (
              <label>
                Цена за 1 млн токенов, $
                <input
                  type="number"
                  min={0.01}
                  step={0.01}
                  value={pricePerMillionTokens}
                  placeholder="3.00"
                  onChange={(event) => setPricePerMillionTokens(event.target.value)}
                />
              </label>
            )}
            {pricingMode === 'monthly' && (
              <label>
                Цена подписки, $/мес
                <input
                  type="number"
                  min={0.01}
                  step={0.01}
                  value={priceMonthly}
                  placeholder="20.00"
                  onChange={(event) => setPriceMonthly(event.target.value)}
                />
              </label>
            )}
            {pricingMode !== 'none' &&
              derivedPriceLabel(
                pricingMode,
                pricePerMillionTokens,
                priceMonthly,
                limitMonthTokens,
              ) != null && (
                <span className="muted pricing-derived">
                  {derivedPriceLabel(pricingMode, pricePerMillionTokens, priceMonthly, limitMonthTokens)}
                </span>
              )}
          </div>
          {pricingMode !== 'none' && (
            <p className="muted">
              Режимы взаимоисключающие: вторая величина рассчитывается из месячного
              лимита, если он задан, и в БД не сохраняется.
            </p>
          )}
          <KeyValueRows
            title="Расширенные настройки — extra-заголовки"
            rows={extraHeaderRows}
            onChange={setExtraHeaderRows}
          />
          <div className="effort-mapper">
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={effortMapperEnabled}
                onChange={(event) => setEffortMapperEnabled(event.target.checked)}
              />
              <strong>Маппер effort-уровней</strong>
              <span className="muted">
                вход low/medium/high/xhigh/max (+ middle/ultra как синонимы) → значения провайдера;
                выключен — уровни пробрасываются как есть
              </span>
            </label>
            {effortMapperEnabled && (
              <div className="effort-mapper-grid">
                {EFFORT_LEVELS.map((level) => (
                  <label key={level}>
                    {level}
                    <input
                      type="text"
                      value={effortLevels[level] ?? ''}
                      placeholder={level}
                      onChange={(event) =>
                        setEffortLevels((current) => ({ ...current, [level]: event.target.value }))
                      }
                    />
                  </label>
                ))}
              </div>
            )}
          </div>
          <div className="overrides-block">
            <div className="key-value-title">Оверрайды входных параметров</div>
            {Object.entries(settingOverrides).map(([key, value]) => {
              const definition = SETTING_CATALOG.find((d) => d.key === key)
              return (
                <div key={key}>
                  <div className="discovered-row">
                    <span className="override-name">
                      {definition?.title ?? key}
                    </span>
                    {(() => {
                      if (definition?.values && definition.values.length > 0) {
                        return (
                          <select
                            className="discovered-public-name"
                            value={value}
                            onChange={(event) =>
                              setSettingOverrides((current) => ({ ...current, [key]: event.target.value }))
                            }
                          >
                            {definition.values.map((variant) => (
                              <option key={variant} value={variant}>
                                {variant}
                              </option>
                            ))}
                          </select>
                        )
                      }
                      return (
                        <input
                          type="text"
                          className="discovered-public-name"
                          value={value}
                          placeholder={definition?.placeholder ?? 'значение'}
                          onChange={(event) =>
                            setSettingOverrides((current) => ({ ...current, [key]: event.target.value }))
                          }
                        />
                      )
                    })()}
                    <button
                      type="button"
                      className="button button-danger button-small"
                      onClick={() =>
                        setSettingOverrides((current) => {
                          const next = { ...current }
                          delete next[key]
                          return next
                        })
                      }
                    >
                      ✕
                    </button>
                  </div>
                  {definition?.description && <FieldHint text={definition.description} />}
                </div>
              )
            })}
            {availableSettingKeys(settingOverrides).length > 0 && (
              <div className="add-override-row">
                <select
                  value={selectedSettingKey}
                  onChange={(event) => setSelectedSettingKey(event.target.value)}
                >
                  <option value="">выберите настройку…</option>
                  {availableSettingKeys(settingOverrides).map((key) => (
                    <option key={key} value={key}>
                      {SETTING_CATALOG.find((d) => d.key === key)?.title ?? key}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="button button-small"
                  disabled={selectedSettingKey.length === 0}
                  onClick={() => {
                    const definition = SETTING_CATALOG.find((d) => d.key === selectedSettingKey)
                    setSettingOverrides((current) => ({
                      ...current,
                      [selectedSettingKey]: definition?.placeholder ?? '',
                    }))
                    setSelectedSettingKey('')
                  }}
                >
                  + добавить
                </button>
              </div>
            )}
          </div>
        </>
      ) : (
        <button type="button" className="button button-small" onClick={() => setShowExtended(true)}>
          Расширенные настройки…
        </button>
      )}
      <div className="discovered-block">
        <div className="form-actions">
          <button
            className="button"
            disabled={discovering || baseUrl.trim().length === 0 || (!isEditMode && apiKey.trim().length === 0)}
            onClick={discoverModels}
            title="Запросить у провайдера список его моделей"
          >
            {discovering ? 'Загрузка…' : 'Загрузить модели провайдера'}
          </button>
          {discoveredModels.length > 0 && (
            <span className="muted">
              найдено: {discoveredModels.length}
              {hiddenAlreadyAddedCount > 0 &&
                ` (ещё ${hiddenAlreadyAddedCount} уже добавлены — скрыты)`}
            </span>
          )}
        </div>
        {discoveredModels.length > 0 && (
          <>
            <div className="discovered-toolbar">
              <span>
                Выберите модели для использования:{' '}
                <strong>
                  {selectedCount} из {discoveredModels.length}
                </strong>
              </span>
              <span className="discovered-toolbar-buttons">
                <button type="button" className="button button-small" onClick={selectAllDiscovered}>
                  Выбрать все
                </button>
                <button type="button" className="button button-small" onClick={clearDiscoveredSelection}>
                  Снять выделение
                </button>
              </span>
            </div>
            <div className="discovered-list">
              {discoveredModels.map((upstream) => (
                <div key={upstream} className="discovered-row">
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={selectedDiscovered[upstream] ?? false}
                      onChange={(event) =>
                        setSelectedDiscovered((current) => ({
                          ...current,
                          [upstream]: event.target.checked,
                        }))
                      }
                    />
                    <code>{upstream}</code>
                  </label>
                  <input
                    type="text"
                    className="discovered-public-name"
                    placeholder="публичное имя (по умолчанию как у провайдера)"
                    value={publicNames[upstream] ?? ''}
                    onChange={(event) =>
                      setPublicNames((current) => ({ ...current, [upstream]: event.target.value }))
                    }
                  />
                </div>
              ))}
            </div>
            <p className="muted">
              Выбранные модели добавятся при сохранении провайдера; reasoning/max-completion/
              приоритет можно донастроить потом в списке моделей.
            </p>
          </>
        )}
      </div>
      <div className="form-actions">
        <button
          className="button"
          disabled={saving || name.trim().length === 0 || baseUrl.trim().length === 0}
          onClick={save}
        >
          {saving ? 'Сохранение…' : 'Сохранить'}
        </button>
        <button className="button" onClick={onCancel}>
          Отмена
        </button>
      </div>
    </div>
  )
}
