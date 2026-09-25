import type { ProviderCost } from '../api/client'
import { formatTokens } from '../format'

// Секция «Стоимость»: тарификация провайдеров — заданная и расчётная цена
// (из месячного лимита), расход и стоимость за 7/30 дней.
export default function CostSection({ providerCosts }: { providerCosts: ProviderCost[] }) {
  if (providerCosts.length === 0) return null
  return (
    <section className="card">
      <h2>
        Стоимость
        <span className="card-note">
          вторая цена — расчётная из месячного лимита; «расч.» = выведена, не задана
        </span>
      </h2>
      <table>
        <thead>
          <tr>
            <th>Провайдер</th>
            <th>Режим</th>
            <th className="numeric">$ / 1М токенов</th>
            <th className="numeric">$ / мес</th>
            <th className="numeric">7 дней</th>
            <th className="numeric">30 дней</th>
          </tr>
        </thead>
        <tbody>
          {providerCosts.map((providerCost) => {
            const cost7Days =
              providerCost.pricePerMillionTokens != null
                ? (providerCost.spentTokens7Days / 1_000_000) * providerCost.pricePerMillionTokens
                : null
            const cost30Days =
              providerCost.pricePerMillionTokens != null
                ? (providerCost.spentTokens30Days / 1_000_000) * providerCost.pricePerMillionTokens
                : null
            return (
              <tr key={providerCost.providerName}>
                <td className="label-cell">{providerCost.providerName}</td>
                <td>
                  {providerCost.pricingMode === 'monthly' ? 'подписка' : 'за 1М токенов'}
                </td>
                <td className="numeric">
                  {providerCost.pricePerMillionTokens != null
                    ? `$${providerCost.pricePerMillionTokens.toFixed(2)}${providerCost.pricePerMillionDerived ? ' (расч.)' : ''}`
                    : '—'}
                </td>
                <td className="numeric">
                  {providerCost.priceMonthly != null
                    ? `$${providerCost.priceMonthly.toFixed(2)}${providerCost.priceMonthlyDerived ? ' (расч.)' : ''}`
                    : '—'}
                </td>
                <td className="numeric">
                  {formatTokens(providerCost.spentTokens7Days)}
                  {cost7Days != null && (
                    <span className="muted"> · ${cost7Days.toFixed(2)}</span>
                  )}
                </td>
                <td className="numeric">
                  {formatTokens(providerCost.spentTokens30Days)}
                  {cost30Days != null && (
                    <span className="muted"> · ${cost30Days.toFixed(2)}</span>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </section>
  )
}
