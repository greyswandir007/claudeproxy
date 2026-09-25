import type { GroupedUsage, ProviderLimitUsage, WindowSummary } from '../api/client'
import { formatTime, formatTokens, totalTokens } from '../format'

// Полоса текущего 5-часового окна ключа: компактно, во всю ширину дашборда —
// прогресс до сброса и токены в одну строку, ниже — 5ч-лимиты провайдеров
// и срез расхода по моделям/провайдерам внутри окна мелкими чипами.
export default function WindowCard({
  clientWindow,
  windowHours,
  windowModels,
  windowProviders,
  providerLimits,
}: {
  clientWindow: WindowSummary | null
  windowHours: number
  windowModels: GroupedUsage[]
  windowProviders: GroupedUsage[]
  providerLimits: ProviderLimitUsage[]
}) {
  const windowLimitProviders = providerLimits.filter(
    (limitUsage) => limitUsage.window !== null,
  )
  if (!clientWindow) {
    return (
      <section className="card window-strip">
        <div className="window-strip-main">
          <span className="window-strip-title">Окно {windowHours} ч</span>
          <span className="muted">не активно — начнётся с первого запроса после простоя</span>
        </div>
      </section>
    )
  }
  const now = Date.now()
  const duration = clientWindow.endsAtMilliseconds - clientWindow.startedAtMilliseconds
  const elapsed = Math.min(Math.max(now - clientWindow.startedAtMilliseconds, 0), duration)
  const progressPercent = Math.round((elapsed / duration) * 100)
  const totals = clientWindow.totals
  const remainingHours = Math.floor((clientWindow.endsAtMilliseconds - now) / 3_600_000)
  const remainingMinutes =
    Math.floor(((clientWindow.endsAtMilliseconds - now) % 3_600_000) / 60_000)
  return (
    <section className="card window-strip">
      <div className="window-strip-main">
        <span className="window-strip-title">Окно {windowHours} ч</span>
        <span className="progress-track window-strip-track" title={`старт ${formatTime(clientWindow.startedAtMilliseconds)}`}>
          <span className="progress-fill" style={{ width: `${progressPercent}%` }} />
        </span>
        <span className="window-strip-remaining muted">
          до сброса {remainingHours} ч {remainingMinutes.toString().padStart(2, '0')} м
        </span>
        <span className="window-strip-stats">
          <span className="window-stat">
            Всего <strong>{formatTokens(totalTokens(totals))}</strong>
          </span>
          <span className="window-stat">
            Вход <strong>{formatTokens(totals.inputTokens)}</strong>
          </span>
          <span className="window-stat">
            Выход <strong>{formatTokens(totals.outputTokens)}</strong>
          </span>
          <span className="window-stat">
            Кэш <strong>{formatTokens(totals.cacheReadTokens)}</strong>
          </span>
          <span className="window-stat">
            Запросов <strong>{formatTokens(totals.requests)}</strong>
          </span>
        </span>
      </div>
      {windowLimitProviders.length > 0 && (
        <div className="window-strip-sub">
          <span className="window-strip-sub-label">Лимиты 5ч:</span>
          {windowLimitProviders.map((limitUsage) => {
            const period = limitUsage.window!
            const percent = Math.min(
              100,
              Math.round((period.spentTokens / period.limitTokens) * 100),
            )
            return (
              <span key={limitUsage.providerName} className="window-chip" title={limitUsage.providerName}>
                <span className="window-chip-bar">
                  <span className="window-chip-fill" style={{ width: `${percent}%` }} />
                </span>
                {limitUsage.providerName} {percent}%
              </span>
            )
          })}
        </div>
      )}
      {(windowModels.length > 0 || windowProviders.length > 0) && (
        <div className="window-strip-sub">
          {windowModels.length > 0 && (
            <>
              <span className="window-strip-sub-label">Модели:</span>
              {windowModels.slice(0, 6).map((row) => (
                <span key={`model-${row.label}`} className="window-chip" title={row.label}>
                  {row.label} · {formatTokens(totalTokens(row))}
                </span>
              ))}
            </>
          )}
          {windowProviders.length > 0 && (
            <>
              <span className="window-strip-sub-label">Провайдеры:</span>
              {windowProviders.slice(0, 6).map((row) => (
                <span key={`provider-${row.label}`} className="window-chip" title={row.label}>
                  {row.label} · {formatTokens(totalTokens(row))}
                </span>
              ))}
            </>
          )}
        </div>
      )}
    </section>
  )
}
