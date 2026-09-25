import type { ProviderWindowSummary, WindowSummary } from '../api/client'
import { formatDateTime, formatTokens, totalTokens } from '../format'

/**
 * История окон: окна клиентского ключа (с перечнем провайдеров, обслуживших
 * каждое окно) и окна провайдеров — у каждого свой независимый отсчёт.
 */
export default function WindowHistoryTable({
  windows,
  providerWindows,
}: {
  windows: WindowSummary[]
  providerWindows: ProviderWindowSummary[]
}) {
  const now = Date.now()
  return (
    <>
      <section className="card table-card">
        <h2>История окон — ключ</h2>
        {windows.length === 0 ? (
          <p className="muted">Окон ещё не было.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Старт</th>
                <th>Конец</th>
                <th>Провайдеры</th>
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
                  <td>
                    {clientWindow.providers.length > 0 ? (
                      clientWindow.providers.map((providerName) => (
                        <span key={providerName} className="window-provider-chip">
                          {providerName}
                        </span>
                      ))
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                  <td className="numeric">{formatTokens(totalTokens(clientWindow.totals))}</td>
                  <td className="numeric">{formatTokens(clientWindow.totals.requests)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {providerWindows.length > 0 && (
        <section className="card table-card">
          <h2>
            История окон — провайдеры
            <span className="card-note">у каждого свой отсчёт от первого обращения</span>
          </h2>
          <table>
            <thead>
              <tr>
                <th>Провайдер</th>
                <th>Старт</th>
                <th>Конец</th>
                <th className="numeric">Токенов</th>
                <th className="numeric">Запросов</th>
              </tr>
            </thead>
            <tbody>
              {providerWindows.map((providerWindow) => (
                <tr key={`${providerWindow.providerName}-${providerWindow.startedAtMilliseconds}`}>
                  <td className="label-cell">{providerWindow.providerName}</td>
                  <td>
                    {formatDateTime(providerWindow.startedAtMilliseconds)}
                    {providerWindow.endsAtMilliseconds > now ? ' · активное' : ''}
                  </td>
                  <td>{formatDateTime(providerWindow.endsAtMilliseconds)}</td>
                  <td className="numeric">{formatTokens(totalTokens(providerWindow.totals))}</td>
                  <td className="numeric">{formatTokens(providerWindow.totals.requests)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </>
  )
}
