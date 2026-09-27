import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { ServerEventView } from '../api/client'

const PAGE_SIZE = 100
const POLL_INTERVAL_MILLISECONDS = 10_000

const LEVELS: Array<{ value: string; label: string }> = [
  { value: '', label: 'Все уровни' },
  { value: 'ERROR', label: 'ERROR' },
  { value: 'WARN', label: 'WARN' },
  { value: 'INFO', label: 'INFO' },
]

// Страница «События»: журнал событий сервера (INFO/WARN/ERROR).
// Строка разворачивается в полную запись: сообщение целиком + stack trace.
export default function ServerEventsPage({ refreshTick }: { refreshTick: number }) {
  const [events, setEvents] = useState<ServerEventView[]>([])
  const [level, setLevel] = useState('')
  const [loggerContains, setLoggerContains] = useState('')
  const [messageContains, setMessageContains] = useState('')
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [expandedEventId, setExpandedEventId] = useState<number | null>(null)
  const [moreAvailable, setMoreAvailable] = useState(false)

  // Текущие фильтры и глубина истории — для автообновления без сброса позиции.
  const filterRef = useRef({ level: '', loggerContains: '', messageContains: '' })
  const pagedDeepRef = useRef(false)
  filterRef.current = { level, loggerContains: loggerContains.trim(), messageContains: messageContains.trim() }

  // Загрузка первой страницы: смена фильтров или глобальное обновление.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    pagedDeepRef.current = false
    api
      .serverEvents({
        level: filterRef.current.level || undefined,
        loggerContains: filterRef.current.loggerContains || undefined,
        messageContains: filterRef.current.messageContains || undefined,
        limit: PAGE_SIZE,
      })
      .then((loaded) => {
        if (cancelled) return
        setEvents(loaded)
        setMoreAvailable(loaded.length === PAGE_SIZE)
      })
      .catch((error: unknown) => {
        if (cancelled) return
        setLoadError(error instanceof Error ? error.message : 'Не удалось загрузить события')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [level, loggerContains, messageContains, refreshTick])

  // Автообновление журнала (~10 c); пока прокручены старые страницы — не трогаем.
  useEffect(() => {
    const pollTimer = window.setInterval(() => {
      if (pagedDeepRef.current) return
      api
        .serverEvents({
          level: filterRef.current.level || undefined,
          loggerContains: filterRef.current.loggerContains || undefined,
          messageContains: filterRef.current.messageContains || undefined,
          limit: PAGE_SIZE,
        })
        .then((loaded) => {
          if (pagedDeepRef.current) return
          setEvents(loaded)
          setMoreAvailable(loaded.length === PAGE_SIZE)
        })
        .catch(() => {})
    }, POLL_INTERVAL_MILLISECONDS)
    return () => window.clearInterval(pollTimer)
  }, [])

  const loadMore = () => {
    if (events.length === 0) return
    setLoading(true)
    pagedDeepRef.current = true
    api
      .serverEvents({
        level: filterRef.current.level || undefined,
        loggerContains: filterRef.current.loggerContains || undefined,
        messageContains: filterRef.current.messageContains || undefined,
        limit: PAGE_SIZE,
        beforeId: events[events.length - 1].id,
      })
      .then((loaded) => {
        setEvents((previous) => [...previous, ...loaded])
        setMoreAvailable(loaded.length === PAGE_SIZE)
      })
      .catch((error: unknown) => {
        setLoadError(error instanceof Error ? error.message : 'Не удалось загрузить события')
      })
      .finally(() => setLoading(false))
  }

  const clearJournal = () => {
    if (!window.confirm('Очистить журнал событий сервера?')) return
    setLoading(true)
    api
      .clearServerEvents()
      .then(() => {
        setEvents([])
        setMoreAvailable(false)
        pagedDeepRef.current = false
      })
      .catch((error: unknown) => {
        setLoadError(error instanceof Error ? error.message : 'Не удалось очистить журнал')
      })
      .finally(() => setLoading(false))
  }

  return (
    <section className="card">
      <h2>
        События сервера
        <span className="card-note">ошибки ключей и провайдеров, кулдауны, фолбэки</span>
      </h2>
      <div className="event-toolbar">
        <select value={level} onChange={(event) => setLevel(event.target.value)}>
          {LEVELS.map((item) => (
            <option key={item.value} value={item.value}>
              {item.label}
            </option>
          ))}
        </select>
        <input
          className="event-filter-input"
          type="text"
          placeholder="источник (класс)"
          value={loggerContains}
          onChange={(event) => setLoggerContains(event.target.value)}
        />
        <input
          className="event-filter-input event-filter-message"
          type="text"
          placeholder="подстрока в сообщении"
          value={messageContains}
          onChange={(event) => setMessageContains(event.target.value)}
        />
        <button type="button" className="button event-clear" onClick={clearJournal} disabled={loading || events.length === 0}>
          Очистить
        </button>
        <span className="muted">
          {loading ? 'загрузка…' : `${events.length} записей (новые сверху)`}
        </span>
      </div>
      {loadError && <div className="load-error">{loadError}</div>}
      {events.length === 0 && !loading && !loadError && (
        <div className="muted empty-events">Событий нет — сервер работает без замечаний.</div>
      )}
      {events.length > 0 && (
        <table className="event-table">
          <thead>
            <tr>
              <th>Время</th>
              <th>Уровень</th>
              <th>Источник</th>
              <th>Сообщение</th>
            </tr>
          </thead>
          <tbody>
            {events.map((event) => {
              const expanded = expandedEventId === event.id
              return (
                <tr
                  key={event.id}
                  className={expanded ? 'event-row event-row-expanded' : 'event-row'}
                  onClick={() => setExpandedEventId(expanded ? null : event.id)}
                >
                  <td className="muted event-time">{event.timestamp}</td>
                  <td>
                    <span className={`event-level level-${event.level.toLowerCase()}`}>
                      {event.level}
                    </span>
                  </td>
                  <td className="label-cell event-source" title={event.logger}>
                    {shortLoggerName(event.logger)}
                  </td>
                  <td className="event-message">
                    <div className="event-message-short">{event.message}</div>
                    {expanded && (
                      <pre className="failure-details">
                        {event.stackTrace
                          ? `${event.message}\n\n${event.stackTrace}`
                          : event.message}
                      </pre>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
      {moreAvailable && (
        <div className="event-more">
          <button type="button" className="button" onClick={loadMore} disabled={loading}>
            Ещё старые…
          </button>
        </div>
      )}
    </section>
  )
}

// «ru.wizard.web.claudeproxy.proxy.impl.WebClientAnthropicHandler» → «WebClientAnthropicHandler».
function shortLoggerName(logger: string): string {
  const lastDot = logger.lastIndexOf('.')
  return lastDot === -1 ? logger : logger.substring(lastDot + 1)
}
