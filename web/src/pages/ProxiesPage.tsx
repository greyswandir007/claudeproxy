import { useEffect, useState } from 'react'
import { api, type ProxyEndpoint } from '../api/client'
import { FieldHint } from '../components/FieldHint'

/**
 * M31: прокси/туннели доступа к провайдерам — список, добавление,
 * редактирование, удаление и проверка соединения. Пароль при
 * редактировании не показывается: пустое поле = оставить прежний.
 */
export default function ProxiesPage({ refreshTick, onRefresh }: { refreshTick: number; onRefresh: () => void }) {
  const [proxies, setProxies] = useState<ProxyEndpoint[]>([])
  const [editing, setEditing] = useState<ProxyEndpoint | null>(null)
  const [adding, setAdding] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [checkingId, setCheckingId] = useState<number | null>(null)

  useEffect(() => {
    api
      .listProxies()
      .then(setProxies)
      .catch((loadError: Error) => setError(loadError.message))
    setAdding(false)
    setEditing(null)
  }, [refreshTick])

  const check = async (id: number) => {
    setCheckingId(id)
    setError(null)
    try {
      await api.checkProxy(id)
      onRefresh()
    } catch (checkError) {
      setError((checkError as Error).message)
    } finally {
      setCheckingId(null)
    }
  }

  const remove = async (proxy: ProxyEndpoint) => {
    setError(null)
    try {
      await api.deleteProxy(proxy.id)
      onRefresh()
    } catch (deleteError) {
      setError((deleteError as Error).message)
    }
  }

  return (
    <div>
      <h1>Прокси</h1>
      <p className="muted">
        Прокси/туннели для исходящих запросов к провайдерам (HTTP, HTTPS-CONNECT,
        SOCKS4, SOCKS5). Привязка выбирается в форме провайдера. Пароль
        поддерживает $&#123;ENV:ИМЯ&#125;-ссылки.
      </p>
      {error && <div className="error-banner">{error}</div>}
      {!adding && editing === null && (
        <button className="button" onClick={() => setAdding(true)}>
          Добавить прокси
        </button>
      )}
      {(adding || editing !== null) && (
        <ProxyForm
          proxy={editing}
          onCancel={() => {
            setAdding(false)
            setEditing(null)
          }}
          onSaved={() => {
            setAdding(false)
            setEditing(null)
            onRefresh()
          }}
        />
      )}
      <table>
        <thead>
          <tr>
            <th>Имя</th>
            <th>Тип</th>
            <th>Адрес</th>
            <th>Вкл.</th>
            <th>Провайдеры</th>
            <th>Проверка</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {proxies.map((proxy) => (
            <tr key={proxy.id}>
              <td>{proxy.name}</td>
              <td>{proxy.type}</td>
              <td>
                {proxy.host}:{proxy.port}
                {proxy.username ? ` (${proxy.username})` : ''}
              </td>
              <td>{proxy.enabled ? 'да' : 'нет'}</td>
              <td>{proxy.providerNames.length > 0 ? proxy.providerNames.join(', ') : '—'}</td>
              <td className="muted">
                {proxy.lastCheckStatus ?? '—'}
                {proxy.lastCheckAt
                  ? ` (${new Date(proxy.lastCheckAt).toLocaleString('ru-RU')})`
                  : ''}
              </td>
              <td>
                <button
                  className="button"
                  disabled={checkingId === proxy.id}
                  onClick={() => check(proxy.id)}
                >
                  {checkingId === proxy.id ? 'Проверяю…' : 'Проверить'}
                </button>{' '}
                <button className="button" onClick={() => setEditing(proxy)}>
                  Изменить
                </button>{' '}
                <button className="button button-danger" onClick={() => remove(proxy)}>
                  Удалить
                </button>
              </td>
            </tr>
          ))}
          {proxies.length === 0 && (
            <tr>
              <td colSpan={7} className="muted">
                Прокси не настроены
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

function ProxyForm({
  proxy,
  onCancel,
  onSaved,
}: {
  proxy: ProxyEndpoint | null
  onCancel: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState(proxy?.name ?? '')
  const [type, setType] = useState(proxy?.type ?? 'HTTP')
  const [host, setHost] = useState(proxy?.host ?? '')
  const [port, setPort] = useState(proxy?.port?.toString() ?? '')
  const [username, setUsername] = useState(proxy?.username ?? '')
  const [password, setPassword] = useState('')
  const [enabled, setEnabled] = useState(proxy?.enabled ?? true)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const save = async () => {
    setSaving(true)
    setError(null)
    const request = {
      name: name.trim(),
      type,
      host: host.trim(),
      port: Number(port),
      username: username.trim(),
      // пусто при редактировании = оставить прежний пароль
      ...(password.trim().length > 0 ? { password: password.trim() } : {}),
      enabled,
    }
    try {
      if (proxy === null) {
        await api.createProxy(request)
      } else {
        await api.updateProxy(proxy.id, request)
      }
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
          <FieldHint text="Уникальное имя; на него ссылаются провайдеры в поле «Прокси»." />
        </label>
        <label>
          Тип:{' '}
          <select value={type} onChange={(event) => setType(event.target.value)}>
            <option value="HTTP">HTTP</option>
            <option value="HTTPS">HTTPS (CONNECT)</option>
            <option value="SOCKS4">SOCKS4</option>
            <option value="SOCKS5">SOCKS5</option>
          </select>
          <FieldHint text="HTTP/HTTPS — CONNECT-туннель (TLS остаётся сквозным); SOCKS — прямая маршрутизация." />
        </label>
        <label>
          Host:{' '}
          <input type="text" value={host} onChange={(event) => setHost(event.target.value)} />
        </label>
        <label>
          Порт:{' '}
          <input
            type="number"
            value={port}
            onChange={(event) => setPort(event.target.value)}
          />
        </label>
        <label>
          Логин:{' '}
          <input type="text" value={username} onChange={(event) => setUsername(event.target.value)} />
          <FieldHint text="Пусто — прокси без авторизации." />
        </label>
        <label>
          Пароль:{' '}
          <input
            type="password"
            value={password}
            placeholder={proxy?.hasPassword ? 'оставить прежний' : ''}
            onChange={(event) => setPassword(event.target.value)}
          />
          <FieldHint text="Допускается ${ENV:ИМЯ}-ссылка. При редактировании пусто = не менять; стереть нельзя, только перезаписать." />
        </label>
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(event) => setEnabled(event.target.checked)}
          />
          Включён
          <FieldHint text="Выключенный прокси не используется — провайдеры идут напрямую." />
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
