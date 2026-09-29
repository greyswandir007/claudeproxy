import type { RangeSummary } from '../api/client'
import { formatTokens, totalTokens } from '../format'

// Карточка периода: итоги + средний расход на 5-часовое окно.
export default function PeriodCard({ summary }: { summary: RangeSummary }) {
  const rangeHours = Math.max(
    1,
    (summary.toMilliseconds - summary.fromMilliseconds) / 3_600_000,
  )
  const averagePerWindow = totalTokens(summary.totals) / (rangeHours / 5)
  const titles: Record<string, string> = {
    today: 'Сегодня',
    week: 'Неделя',
    '7d': '7 дней',
    '30d': '30 дней',
  }
  return (
    <section className="card period-card">
      <h2>{titles[summary.range] ?? summary.range}</h2>
      <div className="period-total">{formatTokens(totalTokens(summary.totals))}</div>
      <div className="muted">токенов всего</div>
      <dl className="period-breakdown">
        <div>
          <dt>Вход</dt>
          <dd>{formatTokens(summary.totals.inputTokens)}</dd>
        </div>
        <div>
          <dt>Выход</dt>
          <dd>{formatTokens(summary.totals.outputTokens)}</dd>
        </div>
        <div>
          <dt>Кэш-чтение</dt>
          <dd>{formatTokens(summary.totals.cacheReadTokens)}</dd>
        </div>
        <div>
          <dt>Запросов</dt>
          <dd>{formatTokens(summary.totals.requests)}</dd>
        </div>
      </dl>
      <div className="period-average">
        в среднем <strong>{formatTokens(Math.round(averagePerWindow))}</strong> за 5 ч
        {summary.totals.savedByTrimming + summary.totals.savedByRequestCache > 0 && (
          <>
            {' '}
            · сэкономлено{' '}
            <strong>
              {formatTokens(summary.totals.savedByTrimming + summary.totals.savedByRequestCache)}
            </strong>
          </>
        )}
      </div>
      {summary.totals.savedByTrimming + summary.totals.savedByRequestCache > 0 && (
        <div className="muted period-savings-detail">
          {(
            [
              ['кэш повторов', summary.totals.savedByRequestCache],
              ['обрезка', summary.totals.savedByTrimming],
            ] as [string, number][]
          )
            .filter(([, tokens]) => tokens > 0)
            .map(([label, tokens]) => `${label} ${formatTokens(tokens)}`)
            .join(' · ')}
        </div>
      )}
    </section>
  )
}
