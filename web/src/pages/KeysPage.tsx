import { useEffect, useState } from 'react'
import { api, type ClientKey, type CreatedKey } from '../api/client'
import KeyQuotaForm from '../components/KeyQuotaForm'
import { formatDateTime, formatTokens } from '../format'

// Экран «Ключи»: генерация с квотами или безлимитом, редактирование квот,
// отзыв. Полный ключ показывается один раз.
export default function KeysPage({ refreshTick }: { refreshTick: number }) {
  const [clientKeys, setClientKeys] = useState<ClientKey[]>([])
  const [creatingKey, setCreatingKey] = useState(false)
  const [editingKeyId, setEditingKeyId] = useState<number | null>(null)
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
      {creatingKey ? (
        <KeyQuotaForm
          onSaved={(createdKey) => {
            setCreatingKey(false)
            if (createdKey) {
              setCreated(createdKey)
              setCopied(false)
            }
            reload()
          }}
          onCancel={() => setCreatingKey(false)}
        />
      ) : (
        editingKeyId === null && (
          <div className="models-toolbar">
            <button className="button" onClick={() => setCreatingKey(true)}>
              + Создать ключ
            </button>
            <span className="muted">безлимитный или с квотами по моделям и токенам</span>
          </div>
        )
      )}
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
                <th>Квоты</th>
                <th>Создан</th>
                <th>Последний запрос</th>
                <th>Статус</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {clientKeys.map((key) =>
                editingKeyId === key.id ? (
                  <tr key={key.id}>
                    <td colSpan={7}>
                      <KeyQuotaForm
                        clientKey={key}
                        onSaved={() => {
                          setEditingKeyId(null)
                          reload()
                        }}
                        onCancel={() => setEditingKeyId(null)}
                      />
                    </td>
                  </tr>
                ) : (
                  <tr key={key.id} className={key.revokedAt ? 'row-revoked' : undefined}>
                    <td className="label-cell">{key.name}</td>
                    <td>
                      <code>{key.keyPrefix}…</code>
                    </td>
                    <td className="muted-cell">
                      {!key.limitWindowTokens && !key.limitMonthTokens
                        ? 'безлимит'
                        : [
                            key.limitWindowTokens
                              ? `окно ${formatTokens(key.limitWindowTokens)}`
                              : null,
                            key.limitMonthTokens
                              ? `мес ${formatTokens(key.limitMonthTokens)}`
                              : null,
                            key.allowedModels.length > 0
                              ? `моделей ${key.allowedModels.length}`
                              : null,
                          ]
                            .filter(Boolean)
                            .join(' · ')}
                    </td>
                    <td>{formatDateTime(key.createdAt)}</td>
                    <td>{key.lastUsedAt ? formatDateTime(key.lastUsedAt) : '—'}</td>
                    <td>{key.revokedAt ? 'отозван' : 'активен'}</td>
                    <td>
                      {key.revokedAt === null && (
                        <>
                          <button
                            className="button button-small"
                            onClick={() => setEditingKeyId(key.id)}
                          >
                            Квоты
                          </button>{' '}
                          <button
                            className="button button-small button-danger"
                            onClick={() => revokeKey(key)}
                          >
                            Отозвать
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}
