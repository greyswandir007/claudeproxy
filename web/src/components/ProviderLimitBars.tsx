import type { ProviderLimitUsage } from '../api/client'
import { formatTokens } from '../format'

// Выработка информационных лимитов провайдера: только заданные категории,
// прогресс-бар «потрачено / лимит» с процентом; под каждым — мини-графики
// моделей относительно того же лимита; перерасход подсвечивается.
export default function ProviderLimitBars({ usage }: { usage: ProviderLimitUsage }) {
  const periods = [
    { title: '5 часов', period: usage.window, note: 'окно провайдера' },
    { title: '7 дней', period: usage.week, note: 'скользящая неделя' },
    { title: '30 дней', period: usage.month, note: 'скользящий месяц' },
  ].filter((entry) => entry.period !== null)
  if (periods.length === 0) return null
  return (
    <div className="limit-bars">
      {periods.map(({ title, period, note }) => {
        if (!period) return null
        const percent = Math.min(100, Math.round((period.spentTokens / period.limitTokens) * 100))
        const overLimit = period.spentTokens > period.limitTokens
        return (
          <div key={title} className="limit-block">
            <div className="limit-row">
              <span className="limit-title" title={note}>
                {title}
              </span>
              <span className="progress-track limit-track">
                <span
                  className={overLimit ? 'progress-fill limit-over' : 'progress-fill'}
                  style={{ width: `${percent}%` }}
                />
              </span>
              <span className={overLimit ? 'limit-value limit-over-text' : 'limit-value'}>
                {formatTokens(period.spentTokens)} / {formatTokens(period.limitTokens)} · {percent}%
                {overLimit ? ' ⚠ перерасход' : ''}
              </span>
            </div>
            {period.modelTokens.length > 0 && (
              <div className="limit-models">
                {period.modelTokens.map((modelEntry) => {
                  const modelPercent = Math.min(
                    100,
                    Math.round((modelEntry.tokens / period.limitTokens) * 100),
                  )
                  return (
                    <div key={modelEntry.modelName} className="limit-model-row">
                      <span className="limit-model-name" title={modelEntry.modelName}>
                        {modelEntry.modelName}
                      </span>
                      <span className="progress-track limit-track limit-model-track">
                        <span className="progress-fill" style={{ width: `${modelPercent}%` }} />
                      </span>
                      <span className="limit-value">
                        {formatTokens(modelEntry.tokens)} · {modelPercent}%
                      </span>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}
