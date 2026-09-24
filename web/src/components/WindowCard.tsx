import type { WindowSummary } from '../api/client'
import { formatDateTime, formatRemaining, formatTokens, totalTokens } from '../format'

// Карточка текущего 5-часового окна ключа: прогресс до сброса + токены.
export default function WindowCard({
  clientWindow,
  windowHours,
}: {
  clientWindow: WindowSummary | null
  windowHours: number
}) {
  if (!clientWindow) {
    return (
      <section className="card window-card">
        <h2>Текущее окно {windowHours} ч</h2>
        <p className="muted">
          Окно не активно — начнётся с первого запроса после простоя.
        </p>
      </section>
    )
  }
  const now = Date.now()
  const duration = clientWindow.endsAtMilliseconds - clientWindow.startedAtMilliseconds
  const elapsed = Math.min(Math.max(now - clientWindow.startedAtMilliseconds, 0), duration)
  const progressPercent = Math.round((elapsed / duration) * 100)
  const totals = clientWindow.totals
  return (
    <section className="card window-card">
      <h2>
        Текущее окно {windowHours} ч
        <span className="card-note">
          до сброса {formatRemaining(clientWindow.endsAtMilliseconds - now)}
        </span>
      </h2>
      <div
        className="progress-track"
        role="progressbar"
        aria-valuenow={progressPercent}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div className="progress-fill" style={{ width: `${progressPercent}%` }} />
      </div>
      <div className="window-meta muted">
        старт {formatDateTime(clientWindow.startedAtMilliseconds)} · пройдено {progressPercent}%
      </div>
      <dl className="token-grid">
        <div>
          <dt>Всего</dt>
          <dd>{formatTokens(totalTokens(totals))}</dd>
        </div>
        <div>
          <dt>Вход</dt>
          <dd>{formatTokens(totals.inputTokens)}</dd>
        </div>
        <div>
          <dt>Выход</dt>
          <dd>{formatTokens(totals.outputTokens)}</dd>
        </div>
        <div>
          <dt>Кэш-чтение</dt>
          <dd>{formatTokens(totals.cacheReadTokens)}</dd>
        </div>
        <div>
          <dt>Запросов</dt>
          <dd>{formatTokens(totals.requests)}</dd>
        </div>
      </dl>
    </section>
  )
}
