import { useEffect, useState } from 'react'
import { api, type ClientKey, type CreatedKey } from '../api/client'
import { formatDateTime } from '../format'

// Экран «Ключи»: генерация, список, отзыв. Полный ключ показывается один раз.
export default function KeysPage({ refreshTick }: { refreshTick: number }) {
  const [clientKeys, setClientKeys] = useState<ClientKey[]>([])
  const [newKeyName, setNewKeyName] = useState('')
  const [created, setCreated] = useState<CreatedKey | null>(null)
  const [copied, setCopied] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const reload = () => {
    api
      .keys()
      .then(setClientKeys)
      .catch((loadError: Error) => setError(loadError.message))
  }

  useEffect(reload, [refreshTick])

  const createKey = () => {
    setError(null)
    api
      .createKey(newKeyName.trim())
      .then((result) => {
        setCreated(result)
        setCopied(false)
        setNewKeyName('')
        reload()
      })
      .catch((createError: Error) => setError(createError.message))
  }

  const copyFullKey = () => {
    if (!created) return
    navigator.clipboard.writeText(created.fullKey).then(() => setCopied(true))
  }

  const revokeKey = (key: ClientKey) => {
    if (!window.confirm(`Отозвать ключ «${key.name}»? Клиенты с ним перестанут проходить auth.`)) {
      return
    }
    api
      .revokeKey(key.id)
      .then(reload)
      .catch((revokeError: Error) => setError(revokeError.message))
  }

  return (
    <div className="keys-page">
      {error && <div className="error-banner">{error}</div>}
      {created && (
        <div className="card created-banner">
          <h2>Ключ «{created.clientKey.name}» создан</h2>
          <p className="muted">
            Полный ключ показывается только один раз — скопируйте его сейчас:
          </p>
          <div className="created-key-row">
            <code>{created.fullKey}</code>
            <button className="button" onClick={copyFullKey}>
              {copied ? 'Скопировано ✓' : 'Копировать'}
            </button>
          </div>
        </div>
      )}
      <section className="card">
        <h2>Новый ключ</h2>
        <div className="create-row">
          <input
            type="text"
            placeholder="имя ключа, например my-laptop"
            value={newKeyName}
            maxLength={64}
            onChange={(event) => setNewKeyName(event.target.value)}
          />
          <button className="button" disabled={newKeyName.trim().length === 0} onClick={createKey}>
            Сгенерировать
          </button>
        </div>
      </section>
      <section className="card table-card">
        <h2>Ключи</h2>
        {clientKeys.length === 0 ? (
          <p className="muted">Ключей нет.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Имя</th>
                <th>Префикс</th>
                <th>Создан</th>
                <th>Последний запрос</th>
                <th>Статус</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {clientKeys.map((key) => (
                <tr key={key.id} className={key.revokedAt ? 'row-revoked' : undefined}>
                  <td className="label-cell">{key.name}</td>
                  <td>
                    <code>{key.keyPrefix}…</code>
                  </td>
                  <td>{formatDateTime(key.createdAt)}</td>
                  <td>{key.lastUsedAt ? formatDateTime(key.lastUsedAt) : '—'}</td>
                  <td>{key.revokedAt ? 'отозван' : 'активен'}</td>
                  <td>
                    {key.revokedAt === null && (
                      <button className="button button-danger" onClick={() => revokeKey(key)}>
                        Отозвать
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}
