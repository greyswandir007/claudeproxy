import { Fragment } from 'react'
import type { GroupedUsage } from '../api/client'
import { formatTokens, totalTokens } from '../format'

// Таблица группировки (модели/провайдеры/ключи): токены, запросы и доля.
export default function UsageTable({
  title,
  rows,
  labelTitle,
}: {
  title: string
  rows: GroupedUsage[]
  labelTitle: string
}) {
  const grandTotal = rows.reduce((sum, row) => sum + totalTokens(row), 0)
  return (
    <section className="card table-card">
      <h2>{title}</h2>
      {rows.length === 0 ? (
        <p className="muted">Нет данных за период.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>{labelTitle}</th>
              <th className="numeric">Запросов</th>
              <th className="numeric">Вход</th>
              <th className="numeric">Выход</th>
              <th className="numeric">Кэш-чтение</th>
              <th className="numeric">Доля</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const share =
                grandTotal > 0 ? Math.round((totalTokens(row) / grandTotal) * 100) : 0
              return (
                <tr key={row.label}>
                  <td className="label-cell">{row.label}</td>
                  <td className="numeric">{formatTokens(row.requests)}</td>
                  <td className="numeric">{formatTokens(row.inputTokens)}</td>
                  <td className="numeric">{formatTokens(row.outputTokens)}</td>
                  <td className="numeric">{formatTokens(row.cacheReadTokens)}</td>
                  <td className="numeric">
                    <Fragment>
                      <span className="share-bar">
                        <span className="share-fill" style={{ width: `${share}%` }} />
                      </span>
                      {share}%
                    </Fragment>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </section>
  )
}
