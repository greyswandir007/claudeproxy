import { useState } from 'react'
import { api, type ManagedProvider } from '../api/client'
import KeyValueRows from './KeyValueRows'

// Форма провайдера. Базовый набор: имя, тип, base-url, api-ключ.
// Расширенный (extra-заголовки): при создании свёрнут, при редактировании — сразу развёрнут.
// «Загрузить модели провайдера» → чек-лист найденных моделей (выбрать все / снять
// выделение, публичное имя для каждой); выбранные добавляются при сохранении.
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
  const [apiKey, setApiKey] = useState('')
  const [extraHeaderRows, setExtraHeaderRows] = useState(
    Object.entries(provider?.extraHeaders ?? {}).map(([key, value]) => ({ key, value })),
  )
  const [limitWindowTokens, setLimitWindowTokens] = useState(
    provider?.limitWindowTokens != null ? String(provider.limitWindowTokens) : '',
  )
  const [limitWeekTokens, setLimitWeekTokens] = useState(
    provider?.limitWeekTokens != null ? String(provider.limitWeekTokens) : '',
  )
  const [limitMonthTokens, setLimitMonthTokens] = useState(
    provider?.limitMonthTokens != null ? String(provider.limitMonthTokens) : '',
  )
  const [showExtended, setShowExtended] = useState(isEditMode)

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

  /** Пустое или неположительное значение — лимит не задан (null). */
  const positiveLimitOrNull = (value: string): number | null => {
    const parsed = Number(value)
    return Number.isFinite(parsed) && parsed > 0 ? Math.round(parsed) : null
  }

  const clearDiscoveredSelection = () =>
    setSelectedDiscovered(
      Object.fromEntries(discoveredModels.map((upstream) => [upstream, false])),
    )

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
      limitWindowTokens: positiveLimitOrNull(limitWindowTokens),
      limitWeekTokens: positiveLimitOrNull(limitWeekTokens),
      limitMonthTokens: positiveLimitOrNull(limitMonthTokens),
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
        </label>
        <label>
          Тип
          <select value={type} onChange={(event) => setType(event.target.value)}>
            <option value="openai">openai (перевод протокола)</option>
            <option value="anthropic">anthropic (pass-through)</option>
          </select>
        </label>
        <label className="form-wide">
          base-url
          <input
            type="text"
            value={baseUrl}
            placeholder="https://openrouter.ai/api/v1"
            onChange={(event) => setBaseUrl(event.target.value)}
          />
        </label>
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
        </label>
      </div>
      {showExtended ? (
        <>
          <div className="form-grid">
            <label>
              Расширенные: лимит токенов — 5 часов
              <input
                type="number"
                min={1}
                value={limitWindowTokens}
                placeholder="не задан"
                onChange={(event) => setLimitWindowTokens(event.target.value)}
              />
            </label>
            <label>
              Лимит токенов — неделя (7 дней)
              <input
                type="number"
                min={1}
                value={limitWeekTokens}
                placeholder="не задан"
                onChange={(event) => setLimitWeekTokens(event.target.value)}
              />
            </label>
            <label>
              Лимит токенов — месяц (30 дней)
              <input
                type="number"
                min={1}
                value={limitMonthTokens}
                placeholder="не задан"
                onChange={(event) => setLimitMonthTokens(event.target.value)}
              />
            </label>
          </div>
          <p className="muted">
            Лимиты информационные — видны на экране «Модели и провайдеры» как выработка;
            можно задать один или несколько, пустое поле = не задан.
          </p>
          <KeyValueRows
            title="Расширенные настройки — extra-заголовки"
            rows={extraHeaderRows}
            onChange={setExtraHeaderRows}
          />
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
