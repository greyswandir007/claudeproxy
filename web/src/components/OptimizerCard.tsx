import { useEffect, useState } from 'react'
import { api, type OptimizerStatsView } from '../api/client'
import KeyValueRows from './KeyValueRows'

/**
 * Модель-оптимизатор (M30): настройка (вкл/выкл, провайдер, модель) и
 * диагностика сжатия старых tool_result. Провайдер выбирается из
 * подключённых; сжатие действует у провайдеров с TRIM_OLD_TOOL_RESULTS.
 */
export default function OptimizerCard({ refreshTick }: { refreshTick: number }) {
  const [providerNames, setProviderNames] = useState<string[]>([])
  const [enabled, setEnabled] = useState(false)
  const [providerName, setProviderName] = useState('')
  const [model, setModel] = useState('')
  const [saveError, setSaveError] = useState<string | null>(null)
  const [savedAt, setSavedAt] = useState<number | null>(null)

  const [rows, setRows] = useState<{ key: string; value: string }[]>([])

  useEffect(() => {
    api
      .optimizerStats()
      .then((loadedStats) => setRows(statsRows(loadedStats)))
      .catch(() => setRows([]))
    Promise.all([api.optimizerConfig(), api.listProviders()])
      .then(([config, providers]) => {
        setEnabled(config.enabled)
        setProviderName(config.providerName ?? '')
        setModel(config.model ?? '')
        setProviderNames(providers.map((provider) => provider.name))
      })
      .catch((loadError: Error) => setSaveError(loadError.message))
  }, [refreshTick, savedAt])

  const save = () => {
    setSaveError(null)
    api
      .updateOptimizerConfig({
        enabled,
        providerName: providerName.length > 0 ? providerName : null,
        model: model.trim().length > 0 ? model.trim() : null,
      })
      .then(() => setSavedAt(Date.now()))
      .catch((saveRequestError: Error) => setSaveError(saveRequestError.message))
  }

  return (
    <section className="card">
      <h2>
        Оптимизатор
        <span className="card-note">
          осмысленное сжатие старых tool_result вместо маркера; действует у
          провайдеров с TRIM_OLD_TOOL_RESULTS
        </span>
      </h2>
      <div className="form-card">
        <div className="form-grid">
          <label className="checkbox-label">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(event) => setEnabled(event.target.checked)}
            />
            Включён
          </label>
          <label>
            Провайдер:{' '}
            <select value={providerName} onChange={(event) => setProviderName(event.target.value)}>
              <option value="">— не выбран —</option>
              {providerNames.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Модель:{' '}
            <input
              type="text"
              value={model}
              placeholder="например, glm-5.3-flash"
              onChange={(event) => setModel(event.target.value)}
            />
          </label>
          <button className="button" onClick={save}>
            Сохранить
          </button>
        </div>
        {saveError && <div className="error-banner">{saveError}</div>}
      </div>
      {rows.length > 0 && (
        <KeyValueRows title="Статистика сжатия" rows={rows} onChange={setRows} />
      )}
    </section>
  )
}

function statsRows(stats: OptimizerStatsView): { key: string; value: string }[] {
  return [
    { key: 'Состояние', value: optimizerStateLabel(stats) },
    { key: 'Сжато блоков', value: stats.compressions.toLocaleString('ru-RU') },
    { key: 'Из кэша', value: stats.cacheHits.toLocaleString('ru-RU') },
    { key: 'Несжимаемо', value: stats.notCompressed.toLocaleString('ru-RU') },
    { key: 'Маркером (отказы/бюджет)', value: stats.fallbacks.toLocaleString('ru-RU') },
    { key: 'Ошибок вызова', value: stats.failures.toLocaleString('ru-RU') },
    {
      key: 'Сэкономлено (оценка, токенов)',
      value: stats.estimatedTokensSaved.toLocaleString('ru-RU'),
    },
    {
      key: 'Потрачено оптимизатором (токенов)',
      value: stats.modelTokensSpent.toLocaleString('ru-RU'),
    },
    {
      key: 'Латентность сжатия',
      value:
        stats.compressions > 0
          ? `в среднем ${stats.averageLatencyMilliseconds} мс, максимум ${stats.maxLatencyMilliseconds} мс`
          : '—',
    },
    { key: 'Последняя ошибка', value: stats.lastError ?? '—' },
  ]
}

function optimizerStateLabel(stats: OptimizerStatsView): string {
  if (!stats.enabled) return 'выключен'
  switch (stats.circuitState) {
    case 'CLOSED':
      return `работает (${stats.providerName} / ${stats.model})`
    case 'OPEN':
      return `breaker открыт: модель недоступна, блоки уходят маркером`
    case 'PROBE':
      return `проба после перерыва (${stats.providerName} / ${stats.model})`
    default:
      return stats.circuitState
  }
}
