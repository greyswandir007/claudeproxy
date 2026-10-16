import { useEffect, useState } from 'react'
import { api, type CacheProviderStatus, type CacheProviderView } from '../api/client'
import { FieldHint } from '../components/FieldHint'

/**
 * Вкладка «Кэш»: внешние кэш-провайдеры по docs/cache-providers.md —
 * список, добавление по URL, статус по спеке и удаление. Встроенный
 * local (request-кэш прокси) показывается первым, без правок.
 * Привязка к провайдеру моделей — настройка CACHE_PROVIDER в его форме.
 */
export default function CachePage({
  refreshTick,
  onRefresh,
}: {
  refreshTick: number
  onRefresh: () => void
}) {
  const [providers, setProviders] = useState<CacheProviderView[]>([])
  const [adding, setAdding] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api
      .listCacheProviders()
      .then(setProviders)
      .catch((loadError: Error) => setError(loadError.message))
    setAdding(false)
  }, [refreshTick])

  const remove = async (provider: CacheProviderView) => {
    setError(null)
    try {
      await api.deleteCacheProvider(provider.id)
      onRefresh()
    } catch (deleteError) {
      setError((deleteError as Error).message)
    }
  }

  return (
    <div>
      <h1>Кэш</h1>
      <p className="muted">
        Кэш-провайдеры (docs/cache-providers.md): local — встроенный кэш
        повторов; внешние сервисы (claudeproxycachedb, claudeproxycachegraph)
        подключаются по URL. Выбор кэша — настройка «Кэш-провайдер» в форме
        провайдера моделей.
      </p>
      {error && <div className="error-banner">{error}</div>}

      <table>
        <thead>
          <tr>
            <th>Имя</th>
            <th>Адрес</th>
            <th>Вкл.</th>
            <th>Статус</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          <CacheProviderRow
            name="local"
            url="встроенный (кэш повторов)"
            enabled
            statusId="local"
          />
          {providers.map((provider) => (
            <CacheProviderRow
              key={provider.id}
              name={provider.name}
              url={provider.url}
              enabled={provider.enabled}
              statusId={provider.id}
              onRemove={() => remove(provider)}
            />
          ))}
          {providers.length === 0 && (
            <tr>
              <td colSpan={5} className="muted">
                Внешние кэш-провайдеры не настроены
              </td>
            </tr>
          )}
        </tbody>
      </table>

      {!adding && (
        <button className="button" onClick={() => setAdding(true)}>
          Добавить кэш-провайдер
        </button>
      )}
      {adding && (
        <CacheProviderForm
          onCancel={() => setAdding(false)}
          onSaved={() => {
            setAdding(false)
            onRefresh()
          }}
        />
      )}
    </div>
  )
}

function CacheProviderRow({
  name,
  url,
  enabled,
  statusId,
  fixed,
  onRemove,
}: {
  name: string
  url: string
  enabled: boolean
  statusId: number | 'local'
  fixed?: boolean
  onRemove?: () => void
}) {
  const [status, setStatus] = useState<CacheProviderStatus | null>(null)
  const [checking, setChecking] = useState(false)

  const check = async () => {
    setChecking(true)
    try {
      setStatus(await api.cacheProviderStatus(statusId))
    } catch (statusError) {
      setStatus({ error: (statusError as Error).message })
    } finally {
      setChecking(false)
    }
  }

  const counters = status?.counters
  return (
    <tr>
      <td>{name}</td>
      <td>{url}</td>
      <td>{enabled ? 'да' : 'нет'}</td>
      <td className="muted">
        {status == null
          ? '—'
          : status.error
            ? status.error
            : `попаданий ${(counters?.hits_exact ?? 0) + (counters?.hits_probable ?? 0)} из ${counters?.lookups ?? 0}`}
      </td>
      <td>
        <button className="button" disabled={checking} onClick={check}>
          {checking ? 'Проверяю…' : 'Статус'}
        </button>{' '}
        {!fixed && onRemove && (
          <button className="button button-danger" onClick={onRemove}>
            Удалить
          </button>
        )}
      </td>
    </tr>
  )
}

function CacheProviderForm({
  onCancel,
  onSaved,
}: {
  onCancel: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState('')
  const [url, setUrl] = useState('http://127.0.0.1:8390')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const save = async () => {
    setSaving(true)
    setError(null)
    try {
      await api.createCacheProvider({ name: name.trim(), url: url.trim() })
      onSaved()
    } catch (saveError) {
      setError((saveError as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="form-card">
      <div className="form-grid">
        <label>
          Имя:{' '}
          <input type="text" value={name} onChange={(event) => setName(event.target.value)} />
          <FieldHint text="Уникальное имя; на него ссылаются провайдеры моделей настройкой «Кэш-провайдер»." />
        </label>
        <label>
          URL:{' '}
          <input type="text" value={url} onChange={(event) => setUrl(event.target.value)} />
          <FieldHint text="Адрес сервиса по docs/cache-providers.md (например, claudeproxycachedb на 8390)." />
        </label>
      </div>
      {error && <div className="error-banner">{error}</div>}
      <button className="button" disabled={saving} onClick={save}>
        {saving ? 'Сохраняю…' : 'Сохранить'}
      </button>{' '}
      <button className="button" onClick={onCancel}>
        Отмена
      </button>
    </div>
  )
}
