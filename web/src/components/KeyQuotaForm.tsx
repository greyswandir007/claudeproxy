import { useEffect, useState } from 'react'
import { api, type ClientKey, type CreatedKey } from '../api/client'
import { FieldHint } from './FieldHint'

// Форма квот ключа: «Безлимит» (по умолчанию) или квоты — allowlist моделей
// и лимиты токенов на окно/месяц (в млн, пусто = безлимит по измерению).
export default function KeyQuotaForm({
  clientKey,
  onSaved,
  onCancel,
}: {
  clientKey?: ClientKey
  onSaved: (createdKey?: CreatedKey) => void
  onCancel: () => void
}) {
  const isEditMode = clientKey !== undefined
  const [name, setName] = useState(clientKey?.name ?? '')
  const [unlimited, setUnlimited] = useState(
    !clientKey?.limitWindowTokens && !clientKey?.limitMonthTokens,
  )
  const [allowedModels, setAllowedModels] = useState<string[]>(
    clientKey?.allowedModels ?? [],
  )
  const [limitWindowTokens, setLimitWindowTokens] = useState(
    clientKey?.limitWindowTokens != null ? String(clientKey.limitWindowTokens / 1_000_000) : '',
  )
  const [limitMonthTokens, setLimitMonthTokens] = useState(
    clientKey?.limitMonthTokens != null ? String(clientKey.limitMonthTokens / 1_000_000) : '',
  )
  const [exposedModels, setExposedModels] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    api
      .config()
      .then((config) => setExposedModels(config.exposedModels))
      .catch(() => setExposedModels([]))
  }, [])

  const save = () => {
    setSaving(true)
    setError(null)
    const request = {
      name: name.trim(),
      ...(unlimited
        ? { allowedModels: [] as string[], limitWindowTokens: null, limitMonthTokens: null }
        : {
            allowedModels,
            limitWindowTokens: millionsToTokens(limitWindowTokens),
            limitMonthTokens: millionsToTokens(limitMonthTokens),
          }),
    }
    const result = isEditMode
      ? api.updateKey(clientKey.id, request).then(() => undefined)
      : api.createKey(request)
    result
      .then((createdKey) => {
        setSaving(false)
        onSaved(createdKey)
      })
      .catch((saveError: Error) => {
        setSaving(false)
        setError(saveError.message)
      })
  }

  return (
    <div className="form-card">
      <h3>{isEditMode ? `Квоты ключа «${clientKey.name}»` : 'Новый ключ'}</h3>
      {error && <div className="error-banner">{error}</div>}
      {!isEditMode && (
        <div className="form-grid">
          <label>
            Имя ключа
            <input
              type="text"
              value={name}
              placeholder="например my-laptop"
              maxLength={64}
              onChange={(event) => setName(event.target.value)}
            />
            <FieldHint text="Ключ примет вид cpk_<имя>-<случайный суффикс>; полный секрет показывается один раз при создании." />
          </label>
        </div>
      )}
      <label className="checkbox-label">
        <input
          type="checkbox"
          checked={unlimited}
          onChange={(event) => setUnlimited(event.target.checked)}
        />
        <strong>Безлимитный доступ</strong>
        <span className="muted">без ограничений по моделям и токенам</span>
      </label>
      {!unlimited && (
        <>
          <div className="form-grid">
            <label>
              Квота токенов на 5-часовое окно, млн
              <input
                type="number"
                min={0.1}
                step={0.1}
                value={limitWindowTokens}
                placeholder="безлимит"
                onChange={(event) => setLimitWindowTokens(event.target.value)}
              />
              <FieldHint text="Скользящее 5-часовое окно — как лимит подписки Claude; при превышении запросы отклоняются до конца окна." />
            </label>
            <label>
              Квота токенов на месяц (30 дней), млн
              <input
                type="number"
                min={0.1}
                step={0.1}
                value={limitMonthTokens}
                placeholder="безлимит"
                onChange={(event) => setLimitMonthTokens(event.target.value)}
              />
              <FieldHint text="Скользящие 30 дней; считается независимо от окна. Пустое поле = безлимит по измерению." />
            </label>
          </div>
          <div className="overrides-block">
            <div className="key-value-title">
              Доступные модели {allowedModels.length > 0 && `— выбрано ${allowedModels.length}`}
            </div>
            <div className="model-chips">
              {exposedModels.map((modelId) => (
                <label key={modelId} className="model-chip model-chip-selectable">
                  <input
                    type="checkbox"
                    checked={allowedModels.includes(modelId)}
                    onChange={(event) =>
                      setAllowedModels((current) =>
                        event.target.checked
                          ? [...current, modelId]
                          : current.filter((entry) => entry !== modelId),
                      )
                    }
                  />
                  {modelId}
                </label>
              ))}
              <p className="muted">
                ничего не выбрано = все модели
              </p>
            </div>
          </div>
        </>
      )}
      <div className="form-actions">
        <button className="button" disabled={saving || (!isEditMode && name.trim().length === 0)} onClick={save}>
          {saving ? 'Сохранение…' : 'Сохранить'}
        </button>
        <button className="button" onClick={onCancel}>
          Отмена
        </button>
      </div>
    </div>
  )
}

/** Ввод в миллионах → токены; пустое/неположительное — безлимит (null). */
const millionsToTokens = (value: string): number | null => {
  const parsed = Number(value.replace(',', '.'))
  return Number.isFinite(parsed) && parsed > 0 ? Math.round(parsed * 1_000_000) : null
}
