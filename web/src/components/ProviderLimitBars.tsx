import type { ProviderLimitUsage } from '../api/client'
import { formatTokens } from '../format'

// Выработка информационных лимитов провайдера: только заданные категории
// (незаданные выводятся из заданных и помечены «расч.»), прогресс-бар
// «потрачено / лимит · %» с минимальной полоской при ненулевом расходе;
// под каждым периодом — мини-графики моделей относительно того же лимита.
export default function ProviderLimitBars({ usage }: { usage: ProviderLimitUsage }) {
  const periods = [
    {
      title: '5 часов',
      period: usage.window,
      note: usage.windowActive
        ? 'активное окно провайдера'
        : 'окно истекло: расход 0, новое начнётся первым запросом',
    },
    { title: '7 дней', period: usage.week, note: 'скользящая неделя' },
    { title: '30 дней', period: usage.month, note: 'скользящий месяц' },
  ].filter((entry) => entry.period !== null)
  if (periods.length === 0) return null
  return (
    <div className="limit-bars">
      {periods.map(({ title, period, note }) => {
        if (!period) return null
        const percent = Math.min(100, Math.round((period.spentTokens / period.limitTokens) * 100))
        // минимальная полоска, чтобы виден ненулевой расход при огромном лимите
        const barPercent = percent === 0 && period.spentTokens > 0 ? 2 : percent
        const overLimit = period.spentTokens > period.limitTokens
        const windowNote =
          title === '5 часов' ? (usage.windowActive ? '' : ' · окно истекло') : ''
        return (
          <div key={title} className="limit-block">
            <div className="limit-row">
              <span className="limit-title" title={note}>
                {title}
              </span>
              <span className="progress-track limit-track">
                <span
                  className={overLimit ? 'progress-fill limit-over' : 'progress-fill'}
                  style={{ width: `${barPercent}%` }}
                />
              </span>
              <span className={overLimit ? 'limit-value limit-over-text' : 'limit-value'}>
                {formatTokens(period.spentTokens)} /{' '}
                {period.derived ? '≈' : ''}
                {formatTokens(period.limitTokens)}
                {period.derived ? ' (расч.)' : ''} · {percent}%
                {overLimit ? ' ⚠ перерасход' : ''}
                {windowNote}
              </span>
            </div>
            {period.modelTokens.length > 0 && (
              <div className="limit-models">
                {period.modelTokens.map((modelEntry) => {
                  const modelPercent = Math.min(
                    100,
                    Math.round((modelEntry.tokens / period.limitTokens) * 100),
                  )
                  const modelBarPercent =
                    modelPercent === 0 && modelEntry.tokens > 0 ? 2 : modelPercent
                  return (
                    <div key={modelEntry.modelName} className="limit-model-row">
                      <span className="limit-model-name" title={modelEntry.modelName}>
                        {modelEntry.modelName}
                      </span>
                      <span className="progress-track limit-track limit-model-track">
                        <span className="progress-fill" style={{ width: `${modelBarPercent}%` }} />
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
