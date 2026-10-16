import { useState } from 'react'
import { api } from '../api/client'
import type { FallbackReport, RouteCooldown } from '../api/client'
import { formatTokens } from '../format'

// Секция «Маршрутизация»: активные кулдауны, здоровье провайдеров
// (ошибки, p50/p95 латентность) и лента последних переключений.
export default function RoutingHealth({
  report,
  cooldowns,
}: {
  report: FallbackReport | null
  cooldowns: RouteCooldown[]
}) {
  const [expandedEventId, setExpandedEventId] = useState<number | null>(null)
  const [errorDetails, setErrorDetails] = useState<Record<number, string | null>>({})

  // Краткий текст ошибки обрезан до 300 символов ещё при записи события;
  // полная расшифровка (тело ответа провайдера/стектрейс) подтягивается
  // отдельным запросом при первом раскрытии строки.
  const toggleErrorDetails = async (eventId: number) => {
    if (expandedEventId === eventId) {
      setExpandedEventId(null)
      return
    }
    setExpandedEventId(eventId)
    if (eventId in errorDetails) return
    try {
      const response = await api.errorDetail(eventId)
      setErrorDetails((previous) => ({ ...previous, [eventId]: response.errorDetail }))
    } catch {
      setErrorDetails((previous) => ({ ...previous, [eventId]: null }))
    }
  }

  if (!report || (report.providers.length === 0 && cooldowns.length === 0)) return null
  return (
    <section className="card">
      <h2>
        Маршрутизация
        <span className="card-note">кулдауны, ошибки и латентность маршрутов</span>
      </h2>
      {cooldowns.length > 0 && (
        <div className="cooldown-badges">
          {cooldowns.map((cooldown) => (
            <span
              key={cooldown.providerName}
              className="cooldown-badge"
              title={cooldown.reason}
            >
              ⏸ {cooldown.providerName} — ещё{' '}
              {Math.max(
                0,
                Math.round((cooldown.cooldownUntilMilliseconds - Date.now()) / 1000),
              )}{' '}
              с
            </span>
          ))}
        </div>
      )}
      {report.providers.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Провайдер</th>
              <th className="numeric">Запросов</th>
              <th className="numeric">Ошибок</th>
              <th className="numeric">Доля</th>
              <th className="numeric">p50, мс</th>
              <th className="numeric">p95, мс</th>
            </tr>
          </thead>
          <tbody>
            {report.providers.map((provider) => {
              const errorShare =
                provider.requests > 0
                  ? Math.round((provider.failedAttempts / provider.requests) * 100)
                  : 0
              return (
                <tr key={provider.providerName}>
                  <td className="label-cell">{provider.providerName}</td>
                  <td className="numeric">{formatTokens(provider.requests)}</td>
                  <td className="numeric">{formatTokens(provider.failedAttempts)}</td>
                  <td className={errorShare > 20 ? 'numeric limit-over-text' : 'numeric'}>
                    {errorShare}%
                  </td>
                  <td className="numeric">{formatTokens(provider.p50DurationMilliseconds)}</td>
                  <td className="numeric">{formatTokens(provider.p95DurationMilliseconds)}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
      {report.recentFailures.length > 0 && (
        <div className="failures-list">
          <div className="window-strip-sub-label">Последние переключения</div>
          {report.recentFailures.slice(0, 6).map((failure) => (
            <div key={failure.id} className="failure-entry">
              <div className="failure-row">
                <span className="muted">
                  {new Date(failure.timestamp).toLocaleTimeString('ru-RU')}
                </span>
                <span className="limit-model-name">
                  {failure.model} · {failure.providerName}
                </span>
                <span className={failure.status >= 500 ? 'limit-over-text' : ''}>
                  {failure.status === 0 ? 'обрыв' : `HTTP ${failure.status}`}
                </span>
                <span className="muted failure-error" title={failure.error}>
                  {failure.error}
                </span>
                <button
                  type="button"
                  className="failure-details-toggle"
                  onClick={() => void toggleErrorDetails(failure.id)}
                >
                  {expandedEventId === failure.id ? 'скрыть' : 'детали'}
                </button>
              </div>
              {expandedEventId === failure.id && (
                <pre className="failure-details">
                  {failure.id in errorDetails
                    ? (errorDetails[failure.id] ?? 'Полная расшифровка не сохранилась (событие записано до M17)')
                    : 'Загрузка…'}
                </pre>
              )}
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
