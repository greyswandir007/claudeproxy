import { useEffect, useState } from 'react'
import { api, type ProxyConfig } from '../api/client'

// Экран «Модели и провайдеры»: read-only вид конфигурации (без ключей провайдеров).
export default function ModelsPage({ refreshTick }: { refreshTick: number }) {
  const [proxyConfig, setProxyConfig] = useState<ProxyConfig | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api
      .config()
      .then(setProxyConfig)
      .catch((loadError: Error) => setError(loadError.message))
  }, [refreshTick])

  if (error) return <div className="error-banner">{error}</div>
  if (!proxyConfig) return <p className="muted">Загрузка…</p>

  return (
    <div className="models-page">
      <section className="card">
        <h2>Поддерживаемые модели</h2>
        <div className="model-chips">
          {proxyConfig.exposedModels.map((model) => (
            <span key={model} className="model-chip">
              {model}
            </span>
          ))}
          {proxyConfig.exposedModels.length === 0 && (
            <p className="muted">Модели не настроены — проверьте config/application.yml.</p>
          )}
        </div>
      </section>
      {proxyConfig.providers.map((provider) => (
        <section key={provider.name} className="card table-card">
          <h2>
            {provider.name}
            <span className="type-badge">{provider.type}</span>
          </h2>
          <p className="muted">{provider.baseUrl}</p>
          <table>
            <thead>
              <tr>
                <th>Публичное имя</th>
                <th>Модель у провайдера</th>
              </tr>
            </thead>
            <tbody>
              {provider.models.map((model) => (
                <tr key={model.public}>
                  <td className="label-cell">{model.public}</td>
                  <td>{model.upstream}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      ))}
    </div>
  )
}
