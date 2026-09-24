import { useEffect, useState } from 'react'
import { api, type ManagedModel, type ManagedProvider } from '../api/client'

// Форма модели. Базовый набор: публичное имя, upstream-имя (выбор из списка
// моделей провайдера — список подгружается автоматически при открытии формы
// по сохранённому ключу провайдера). Расширенный (reasoning, max-completion,
// приоритет fallback): при создании свёрнут, при редактировании — сразу развёрнут.
export default function ModelForm({
  provider,
  model,
  suggestedUpstreamModels,
  onSaved,
  onCancel,
}: {
  provider: ManagedProvider
  model?: ManagedModel
  suggestedUpstreamModels?: string[]
  onSaved: () => void
  onCancel: () => void
}) {
  const providerId = provider.id
  const isEditMode = model !== undefined
  const [publicName, setPublicName] = useState(model?.publicName ?? '')
  const [upstreamName, setUpstreamName] = useState(model?.upstreamName ?? '')
  // «публичное имя как исходное»: включено по умолчанию при добавлении,
  // при редактировании — если имена сейчас совпадают
  const [publicNameMatchesUpstream, setPublicNameMatchesUpstream] = useState(
    model ? model.publicName === model.upstreamName : true,
  )
  const [reasoning, setReasoning] = useState(model?.reasoning ?? 'map')
  const [maxCompletionParam, setMaxCompletionParam] = useState(model?.maxCompletionParam ?? false)
  const [priority, setPriority] = useState(model?.priority ?? 100)
  const [showExtended, setShowExtended] = useState(isEditMode)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  // Автозагрузка списка моделей провайдера (по сохранённому ключу, providerId).
  const [autoDiscoveredModels, setAutoDiscoveredModels] = useState<string[]>([])
  const [discovering, setDiscovering] = useState(true)
  useEffect(() => {
    let cancelled = false
    setDiscovering(true)
    api
      .discoverModels({ type: provider.type, baseUrl: provider.baseUrl, providerId: provider.id })
      .then((result) => {
        if (!cancelled) {
          setAutoDiscoveredModels(result.models)
          setDiscovering(false)
        }
      })
      .catch(() => {
        // провайдер недоступен — просто без подсказок
        if (!cancelled) setDiscovering(false)
      })
    return () => {
      cancelled = true
    }
  }, [provider.id, provider.type, provider.baseUrl])

  const selectionModels = Array.from(
    new Set([...(suggestedUpstreamModels ?? []), ...autoDiscoveredModels]),
  )

  const save = () => {
    setSaving(true)
    setError(null)
    const request = {
      publicName: (publicNameMatchesUpstream ? upstreamName : publicName).trim(),
      upstreamName: upstreamName.trim(),
      reasoning,
      maxCompletionParam,
      priority: Math.max(1, Math.min(100_000, Math.round(priority) || 100)),
    }
    const result = isEditMode
      ? api.updateModel(model.id, request)
      : api.createModel(providerId, request)
    result
      .then(() => {
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
      <h3>{isEditMode ? `Редактирование «${model.publicName}»` : 'Новая модель'}</h3>
      {error && <div className="error-banner">{error}</div>}
      <div className="form-grid">
        <div className="form-field">
          <label>
            Публичное имя (видит клиент)
            <input
              type="text"
              value={publicNameMatchesUpstream ? upstreamName : publicName}
              disabled={publicNameMatchesUpstream}
              placeholder="например gpt-5.2"
              onChange={(event) => setPublicName(event.target.value)}
            />
          </label>
          <label className="checkbox-label">
            <input
              type="checkbox"
              checked={publicNameMatchesUpstream}
              onChange={(event) => {
                if (!event.target.checked) {
                  // при снятии галочки продолжаем с текущего (производного) имени
                  setPublicName(upstreamName)
                }
                setPublicNameMatchesUpstream(event.target.checked)
              }}
            />
            публичное имя — как у провайдера
          </label>
        </div>
        <label>
          Модель у провайдера
          {selectionModels.length > 0 ? (
            <>
              <input
                type="text"
                list="upstream-model-suggestions"
                value={upstreamName}
                placeholder="выберите из списка или введите"
                onChange={(event) => setUpstreamName(event.target.value)}
              />
              <datalist id="upstream-model-suggestions">
                {selectionModels.map((suggestedModel) => (
                  <option key={suggestedModel} value={suggestedModel} />
                ))}
              </datalist>
              <span className="muted">
                {selectionModels.length} моделей провайдера доступны для выбора
              </span>
            </>
          ) : (
            <input
              type="text"
              value={upstreamName}
              placeholder={discovering ? 'загружаем список моделей провайдера…' : 'например openai/gpt-5.2'}
              onChange={(event) => setUpstreamName(event.target.value)}
            />
          )}
        </label>
      </div>
      {showExtended ? (
        <div className="form-grid">
          <label>
            Расширенные: thinking → reasoning_effort
            <select value={reasoning} onChange={(event) => setReasoning(event.target.value)}>
              <option value="map">map (переводить)</option>
              <option value="off">off (не переводить)</option>
            </select>
          </label>
          <label>
            Приоритет (меньше = выше; fallback при ошибках)
            <input
              type="number"
              min={1}
              max={100000}
              value={priority}
              onChange={(event) => setPriority(Number(event.target.value))}
            />
          </label>
          <label className="checkbox-label">
            <input
              type="checkbox"
              checked={maxCompletionParam}
              onChange={(event) => setMaxCompletionParam(event.target.checked)}
            />
            шлёт max_completion_tokens (o-серия)
          </label>
        </div>
      ) : (
        <button type="button" className="button button-small" onClick={() => setShowExtended(true)}>
          Расширенные настройки…
        </button>
      )}
      <div className="form-actions">
        <button
          className="button"
          disabled={
            saving ||
            upstreamName.trim().length === 0 ||
            (!publicNameMatchesUpstream && publicName.trim().length === 0)
          }
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
