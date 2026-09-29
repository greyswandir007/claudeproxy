import type { GroupedUsage } from '../api/client'
import { formatTokens, totalTokens } from '../format'

interface MergedRow {
  label: string
  week: GroupedUsage | null
  month: GroupedUsage | null
}

// Таблица группировки (модели/провайдеры) с двумя периодами: неделя и 30 дней.
export default function UsageTable({
  title,
  rowsWeek,
  rowsMonth,
  labelTitle,
}: {
  title: string
  rowsWeek: GroupedUsage[]
  rowsMonth: GroupedUsage[]
  labelTitle: string
}) {
  const merged = new Map<string, MergedRow>()
  for (const row of rowsWeek) {
    merged.set(row.label, { label: row.label, week: row, month: null })
  }
  for (const row of rowsMonth) {
    const existing = merged.get(row.label)
    if (existing) {
      existing.month = row
    } else {
      merged.set(row.label, { label: row.label, week: null, month: row })
    }
  }
  const grandTotal =
    rowsWeek.reduce((sum, row) => sum + totalTokens(row), 0) +
    rowsMonth.reduce((sum, row) => sum + totalTokens(row), 0)
  const rows = [...merged.values()].sort((first, second) => {
    const firstTokens = first.week ? totalTokens(first.week) : totalTokens(first.month!)
    const secondTokens = second.week ? totalTokens(second.week) : totalTokens(second.month!)
    return secondTokens - firstTokens
  })
  return (
    <section className="card table-card">
      <h2>{title}</h2>
      {rows.length === 0 ? (
        <p className="muted">Нет данных за период.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th rowSpan={2}>{labelTitle}</th>
              <th colSpan={3} className="numeric">Неделя</th>
              <th colSpan={2} className="numeric">30 дней</th>
            </tr>
            <tr>
              <th className="numeric">Токенов</th>
              <th className="numeric">Вход / Выход</th>
              <th className="numeric">Запросов</th>
              <th className="numeric">Токенов</th>
              <th className="numeric">Запросов</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const weekTokens = row.week ? totalTokens(row.week) : 0
              const share = grandTotal > 0 ? Math.round((weekTokens / grandTotal) * 100) : 0
              return (
                <tr key={row.label}>
                  <td className="label-cell">{row.label}</td>
                  <td className="numeric">
                    <span className="share-bar">
                      <span className="share-fill" style={{ width: `${share}%` }} />
                    </span>
                    {formatTokens(weekTokens)}
                  </td>
                  <td className="numeric muted-cell">
                    {row.week
                      ? `${formatTokens(row.week.inputTokens)} / ${formatTokens(row.week.outputTokens)}`
                      : '—'}
                  </td>
                  <td className="numeric">{row.week ? formatTokens(row.week.requests) : '—'}</td>
                  <td className="numeric">
                    {row.month ? formatTokens(totalTokens(row.month)) : '—'}
                  </td>
                  <td className="numeric">{row.month ? formatTokens(row.month.requests) : '—'}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </section>
  )
}
