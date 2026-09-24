import { useCallback, useEffect, useState } from 'react'
import { api, type ManagedProvider } from '../api/client'

// Экран «Выдача моделей»: что отдавать в GET /v1/models.
// Секции по типу провайдера (Claude = anthropic, OpenAI = openai);
// выбор — целиком провайдер или отдельные модели. Скрытые модели остаются
// маршрутизируемыми — они просто не попадают в список.
export default function ExposurePage({ refreshTick }: { refreshTick: number }) {
  const [providers, setProviders] = useState<ManagedProvider[]>([])
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(() => {
    api
      .listProviders()
      .then((loadedProviders) => {
        setProviders(loadedProviders)
        setError(null)
      })
      .catch((loadError: Error) => setError(loadError.message))
  }, [])

  useEffect(reload, [refreshTick, reload])

  const toggleProvider = (provider: ManagedProvider) => {
    api
      .setProviderExposed(provider.id, !provider.exposed)
      .then(reload)
      .catch((toggleError: Error) => setError(toggleError.message))
  }

  const toggleModel = (modelId: number, exposed: boolean) => {
    api
      .setModelExposed(modelId, !exposed)
      .then(reload)
      .catch((toggleError: Error) => setError(toggleError.message))
  }

  const sections = [
    {
      title: 'Claude (anthropic-провайдеры)',
      providerType: 'anthropic',
      hint: 'модели, выдаваемые клиентам, которые ждут модели Claude',
    },
    {
      title: 'OpenAI (openai-провайдеры)',
      providerType: 'openai',
      hint: 'модели, выдаваемые через перевод протокола',
    },
  ]

  return (
    <div className="exposure-page">
      {error && <div className="error-banner">{error}</div>}
      <section className="card">
        <h2>Выдача моделей — GET /v1/models</h2>
        <p className="muted">
          Отмеченное отдаётся клиентам в списке моделей. Скрытые модели и провайдеры
          остаются доступными по прямому запросу — меняется только список.
        </p>
      </section>
      {sections.map((section) => {
        const sectionProviders = providers.filter(
          (provider) => provider.type === section.providerType,
        )
        return (
          <section key={section.providerType} className="card">
            <h2>
              {section.title}
              <span className="card-note">{section.hint}</span>
            </h2>
            {sectionProviders.length === 0 && <p className="muted">Провайдеров этого типа нет.</p>}
            {sectionProviders.map((provider) => (
              <div key={provider.id} className="exposure-provider">
                <label className="checkbox-label exposure-provider-name">
                  <input
                    type="checkbox"
                    checked={provider.exposed}
                    onChange={() => toggleProvider(provider)}
                  />
                  <strong>{provider.name}</strong>
                  <span className="muted">{provider.baseUrl}</span>
                </label>
                <div className="exposure-models">
                  {provider.models.map((model) => (
                    <label key={model.id} className="checkbox-label">
                      <input
                        type="checkbox"
                        checked={model.exposed}
                        disabled={!provider.exposed}
                        onChange={() => toggleModel(model.id, model.exposed)}
                      />
                      <code>{model.publicName}</code>
                      <span className="muted">← {model.upstreamName}</span>
                    </label>
                  ))}
                  {provider.models.length === 0 && (
                    <span className="muted">моделей нет</span>
                  )}
                </div>
              </div>
            ))}
          </section>
        )
      })}
    </div>
  )
}
