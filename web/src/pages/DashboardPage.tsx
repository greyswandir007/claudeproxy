import { useEffect, useMemo, useState } from 'react'
import {
  api,
  type ClientKey,
  type GroupedUsage,
  type ProxyConfig,
  type RangeSummary,
  type TimelinePoint,
  type WindowSummary,
} from '../api/client'
import PeriodCard from '../components/PeriodCard'
import TimelineChart from '../components/TimelineChart'
import UsageTable from '../components/UsageTable'
import WindowCard from '../components/WindowCard'
import WindowHistoryTable from '../components/WindowHistoryTable'

/** Сентинел «Все ключи» в селекторе (пустая строка). */
const ALL_KEYS_VALUE = ''

// Главный экран: окно 5ч, периоды, таймлайн, таблицы, история окон.
export default function DashboardPage({ refreshTick }: { refreshTick: number }) {
  const [clientKeys, setClientKeys] = useState<ClientKey[]>([])
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [proxyConfig, setProxyConfig] = useState<ProxyConfig | null>(null)
  const [clientWindow, setClientWindow] = useState<WindowSummary | null>(null)
  const [summaries, setSummaries] = useState<RangeSummary[]>([])
  const [byModelRows, setByModelRows] = useState<GroupedUsage[]>([])
  const [byProviderRows, setByProviderRows] = useState<GroupedUsage[]>([])
  const [windows, setWindows] = useState<WindowSummary[]>([])
  const [timelineBucket, setTimelineBucket] = useState<'hour' | 'day'>('hour')
  const [timelinePoints, setTimelinePoints] = useState<TimelinePoint[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api
      .keys()
      .then((keys) => {
        const activeKeys = keys.filter((key) => key.revokedAt === null)
        setClientKeys(activeKeys)
        // по умолчанию — самый активный ключ (не свежесозданный без трафика);
        // уже сделанный пользователем выбор не перекрываем
        setSelectedKey((current) => {
          if (current !== null) return current
          const mostActive = [...activeKeys].sort(
            (first, second) =>
              (second.lastUsedAt ?? second.createdAt) - (first.lastUsedAt ?? first.createdAt),
          )[0]
          return mostActive?.name ?? ALL_KEYS_VALUE
        })
      })
      .catch((loadError: Error) => setError(loadError.message))
    api
      .config()
      .then(setProxyConfig)
      .catch(() => setProxyConfig(null))
  }, [refreshTick])

  useEffect(() => {
    if (selectedKey === null) return // ключи ещё не загрузились
    const keyParameter = selectedKey.length > 0 ? selectedKey : null
    const summariesRequest = Promise.all([
      api.summary('today', keyParameter),
      api.summary('7d', keyParameter),
      api.summary('30d', keyParameter),
      api.byModel('7d', keyParameter),
      api.byProvider('7d', keyParameter),
    ])
    // окно — атрибут конкретного ключа; для «Все ключи» не показываем
    const windowsRequest: Promise<[WindowSummary | null, WindowSummary[]]> = keyParameter
      ? Promise.all([api.currentWindow(keyParameter), api.windowHistory(keyParameter)])
      : Promise.resolve([null, []])
    Promise.all([summariesRequest, windowsRequest])
      .then(
        ([
          [todaySummary, weekSummary, monthSummary, models, providers],
          [currentWindow, windowHistory],
        ]) => {
          setClientWindow(currentWindow)
          setSummaries([todaySummary, weekSummary, monthSummary])
          setByModelRows(models)
          setByProviderRows(providers)
          setWindows(windowHistory)
          setError(null)
        },
      )
      .catch((loadError: Error) => setError(loadError.message))
  }, [refreshTick, selectedKey])

  useEffect(() => {
    if (selectedKey === null) return // ключи ещё не загрузились
    api
      .timeline(timelineBucket, selectedKey.length > 0 ? selectedKey : null)
      .then(setTimelinePoints)
      .catch((loadError: Error) => setError(loadError.message))
  }, [refreshTick, timelineBucket, selectedKey])

  const windowHours = useMemo(() => proxyConfig?.windowHours ?? 5, [proxyConfig])
  const keyParameter = selectedKey !== null && selectedKey.length > 0 ? selectedKey : null

  return (
    <div className="dashboard">
      {error && <div className="error-banner">{error}</div>}
      <div className="key-selector">
        <label>
          Клиентский ключ:{' '}
          <select
            value={selectedKey ?? ALL_KEYS_VALUE}
            onChange={(event) => setSelectedKey(event.target.value)}
          >
            <option value={ALL_KEYS_VALUE}>Все ключи (сводно)</option>
            {clientKeys.map((key) => (
              <option key={key.id} value={key.name}>
                {key.name}
                {key.lastUsedAt ? '' : ' (без обращений)'}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="cards-row">
        {keyParameter ? (
          <WindowCard clientWindow={clientWindow} windowHours={windowHours} />
        ) : (
          <section className="card window-card">
            <h2>Текущее окно {windowHours} ч</h2>
            <p className="muted">
              Окно считается на каждый ключ отдельно — выберите ключ, чтобы увидеть его окно.
            </p>
          </section>
        )}
        {summaries.map((summary) => (
          <PeriodCard key={summary.range} summary={summary} />
        ))}
      </div>
      <div className="chart-controls">
        <div className="segmented">
          <button
            className={timelineBucket === 'hour' ? 'segment segment-active' : 'segment'}
            onClick={() => setTimelineBucket('hour')}
          >
            7 дней по часам
          </button>
          <button
            className={timelineBucket === 'day' ? 'segment segment-active' : 'segment'}
            onClick={() => setTimelineBucket('day')}
          >
            30 дней по дням
          </button>
        </div>
      </div>
      <TimelineChart points={timelinePoints} bucket={timelineBucket} />
      <div className="tables-row">
        <UsageTable title="По моделям (7 дней)" rows={byModelRows} labelTitle="Модель" />
        <UsageTable title="По провайдерам (7 дней)" rows={byProviderRows} labelTitle="Провайдер" />
      </div>
      {keyParameter && <WindowHistoryTable windows={windows} />}
    </div>
  )
}
