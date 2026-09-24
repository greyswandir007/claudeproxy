import type { WindowSummary } from '../api/client'
import { formatDateTime, formatTokens, totalTokens } from '../format'

// История 5-часовых окон ключа.
export default function WindowHistoryTable({
  windows,
}: {
  windows: WindowSummary[]
}) {
  const now = Date.now()
  return (
    <section className="card table-card">
      <h2>История окон</h2>
      {windows.length === 0 ? (
        <p className="muted">Окон ещё не было.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Старт</th>
              <th>Конец</th>
              <th className="numeric">Токенов</th>
              <th className="numeric">Запросов</th>
            </tr>
          </thead>
          <tbody>
            {windows.map((clientWindow) => (
              <tr key={clientWindow.startedAtMilliseconds}>
                <td>
                  {formatDateTime(clientWindow.startedAtMilliseconds)}
                  {clientWindow.endsAtMilliseconds > now ? ' · активное' : ''}
                </td>
                <td>{formatDateTime(clientWindow.endsAtMilliseconds)}</td>
                <td className="numeric">{formatTokens(totalTokens(clientWindow.totals))}</td>
                <td className="numeric">{formatTokens(clientWindow.totals.requests)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
