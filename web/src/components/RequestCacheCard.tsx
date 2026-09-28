import type { RequestCacheStats } from '../api/client'

// Карточка «Кэш повторов»: счётчики обращений/попаданий/промахов
// с момент старта сервера. Причина промаха подсказывает, почему
// повтор не взялся из кэша: записи не было вовсе или она истекла.
export default function RequestCacheCard({ stats }: { stats: RequestCacheStats | null }) {
  if (stats === null) return null
  const hitShare = stats.lookups > 0 ? Math.round((stats.hits / stats.lookups) * 100) : null
  return (
    <section className="card">
      <h2>
        Кэш повторов
        <span className="card-note">счётчики с момента старта сервера</span>
      </h2>
      <table className="table">
        <thead>
          <tr>
            <th>обращений</th>
            <th>попаданий</th>
            <th>доля</th>
            <th>промахов</th>
            <th>нет записи</th>
            <th>запись истекла</th>
            <th>записано</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>{stats.lookups.toLocaleString('ru-RU')}</td>
            <td>{stats.hits.toLocaleString('ru-RU')}</td>
            <td>{hitShare === null ? '—' : `${hitShare}%`}</td>
            <td>{stats.misses.toLocaleString('ru-RU')}</td>
            <td>{stats.missesNoEntry.toLocaleString('ru-RU')}</td>
            <td>{stats.missesExpired.toLocaleString('ru-RU')}</td>
            <td>{stats.stored.toLocaleString('ru-RU')}</td>
          </tr>
        </tbody>
      </table>
    </section>
  )
}
