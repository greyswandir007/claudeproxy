import { useCallback, useEffect, useState } from 'react'
import { api, type ManagedModel, type ManagedProvider, type ProxyConfig } from '../api/client'
import ModelForm from '../components/ModelForm'
import ProviderForm from '../components/ProviderForm'

// Экран «Модели и провайдеры»: CRUD провайдеров и моделей с хранением в БД.
// Изменения применяются сразу — реестр перезагружается после каждой мутации.
export default function ModelsPage({ refreshTick }: { refreshTick: number }) {
  const [providers, setProviders] = useState<ManagedProvider[]>([])
  const [proxyConfig, setProxyConfig] = useState<ProxyConfig | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [creatingProvider, setCreatingProvider] = useState(false)
  const [editingProviderId, setEditingProviderId] = useState<number | null>(null)
  const [addingModelForProviderId, setAddingModelForProviderId] = useState<number | null>(null)
  const [editingModelId, setEditingModelId] = useState<number | null>(null)
  const [discoveredUpstreamModels, setDiscoveredUpstreamModels] = useState<string[]>([])

  const reload = useCallback(() => {
    api
      .listProviders()
      .then((loadedProviders) => {
        setProviders(loadedProviders)
        setError(null)
      })
      .catch((loadError: Error) => setError(loadError.message))
    api
      .config()
      .then(setProxyConfig)
      .catch(() => setProxyConfig(null))
  }, [])

  useEffect(reload, [refreshTick, reload])

  const deleteProvider = (provider: ManagedProvider) => {
    if (
      !window.confirm(
        `Удалить провайдер «${provider.name}» вместе с моделями (${provider.models.length})?`,
      )
    ) {
      return
    }
    api
      .deleteProvider(provider.id)
      .then(reload)
      .catch((deleteError: Error) => setError(deleteError.message))
  }

  const deleteModel = (model: ManagedModel) => {
    if (!window.confirm(`Удалить модель «${model.publicName}»?`)) return
    api
      .deleteModel(model.id)
      .then(reload)
      .catch((deleteError: Error) => setError(deleteError.message))
  }

  return (
    <div className="models-page">
      {error && <div className="error-banner">{error}</div>}
      <section className="card">
        <h2>
          Поддерживаемые модели
          <span className="card-note">
            {proxyConfig?.exposedModels.length ?? 0} шт. · применяются сразу после сохранения
          </span>
        </h2>
        <div className="model-chips">
          {(proxyConfig?.exposedModels ?? []).map((model) => (
            <span key={model} className="model-chip">
              {model}
            </span>
          ))}
          {(proxyConfig?.exposedModels.length ?? 0) === 0 && (
            <p className="muted">Моделей нет — добавьте провайдера ниже.</p>
          )}
        </div>
      </section>

      {creatingProvider ? (
        <ProviderForm
          onDiscovered={setDiscoveredUpstreamModels}
          onSaved={() => {
            setCreatingProvider(false)
            reload()
          }}
          onCancel={() => setCreatingProvider(false)}
        />
      ) : (
        <div className="models-toolbar">
          <button className="button" onClick={() => setCreatingProvider(true)}>
            + Добавить провайдера
          </button>
          <span className="muted">
            провайдеры и модели хранятся в БД; config/application.yml используется как сид при старте
          </span>
        </div>
      )}

      {providers.map((provider) => {
        const isEditingProvider = editingProviderId === provider.id
        return (
          <section key={provider.id} className="card table-card">
            {isEditingProvider ? (
              <ProviderForm
                provider={provider}
                onDiscovered={setDiscoveredUpstreamModels}
                onSaved={() => {
                  setEditingProviderId(null)
                  reload()
                }}
                onCancel={() => setEditingProviderId(null)}
              />
            ) : (
              <>
                <h2>
                  {provider.name}
                  <span className="type-badge">{provider.type}</span>
                  <span className="card-note">
                    {provider.baseUrl} · ключ: {provider.apiKeyPreview || '—'}
                  </span>
                  <span className="card-actions">
                    <button
                      className="button button-small"
                      onClick={() => setEditingProviderId(provider.id)}
                    >
                      Изменить
                    </button>
                    <button
                      className="button button-small button-danger"
                      onClick={() => deleteProvider(provider)}
                    >
                      Удалить
                    </button>
                  </span>
                </h2>
                {provider.models.length === 0 ? (
                  <p className="muted">Моделей нет.</p>
                ) : (
                  <table>
                    <thead>
                      <tr>
                        <th>Публичное имя</th>
                        <th>Модель у провайдера</th>
                        <th>reasoning</th>
                        <th>max_completion</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      {provider.models.map((model) =>
                        editingModelId === model.id ? null : (
                          <tr key={model.id}>
                            <td className="label-cell">{model.publicName}</td>
                            <td>{model.upstreamName}</td>
                            <td>{model.reasoning}</td>
                            <td>{model.maxCompletionParam ? 'да' : 'нет'}</td>
                            <td className="row-actions">
                              <button
                                className="button button-small"
                                onClick={() => setEditingModelId(model.id)}
                              >
                                Изменить
                              </button>
                              <button
                                className="button button-small button-danger"
                                onClick={() => deleteModel(model)}
                              >
                                Удалить
                              </button>
                            </td>
                          </tr>
                        ),
                      )}
                    </tbody>
                  </table>
                )}
                {editingModelId !== null &&
                  provider.models.some((model) => model.id === editingModelId) && (
                    <ModelForm
                      provider={provider}
                      model={provider.models.find((model) => model.id === editingModelId)}
                      suggestedUpstreamModels={discoveredUpstreamModels}
                      onSaved={() => {
                        setEditingModelId(null)
                        reload()
                      }}
                      onCancel={() => setEditingModelId(null)}
                    />
                  )}
                {addingModelForProviderId === provider.id ? (
                  <ModelForm
                    provider={provider}
                    suggestedUpstreamModels={discoveredUpstreamModels}
                    onSaved={() => {
                      setAddingModelForProviderId(null)
                      reload()
                    }}
                    onCancel={() => setAddingModelForProviderId(null)}
                  />
                ) : (
                  editingModelId === null && (
                    <button
                      className="button button-small"
                      onClick={() => setAddingModelForProviderId(provider.id)}
                    >
                      + Добавить модель
                    </button>
                  )
                )}
              </>
            )}
          </section>
        )
      })}
      {providers.length === 0 && !creatingProvider && (
        <p className="muted">Провайдеров нет — добавьте первого.</p>
      )}
    </div>
  )
}
