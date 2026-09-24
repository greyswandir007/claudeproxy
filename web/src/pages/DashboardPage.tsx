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
        setSelectedKey((current) => current ?? activeKeys[0]?.name ?? null)
      })
      .catch((loadError: Error) => setError(loadError.message))
    api
      .config()
      .then(setProxyConfig)
      .catch(() => setProxyConfig(null))
  }, [refreshTick])

  useEffect(() => {
    if (!selectedKey) return
    Promise.all([
      api.currentWindow(selectedKey),
      api.summary('today', selectedKey),
      api.summary('7d', selectedKey),
      api.summary('30d', selectedKey),
      api.byModel('7d', selectedKey),
      api.byProvider('7d', selectedKey),
      api.windowHistory(selectedKey),
    ])
      .then(
        ([currentWindow, todaySummary, weekSummary, monthSummary, models, providers, windowHistory]) => {
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
    api
      .timeline(timelineBucket)
      .then(setTimelinePoints)
      .catch((loadError: Error) => setError(loadError.message))
  }, [refreshTick, timelineBucket])

  const windowHours = useMemo(() => proxyConfig?.windowHours ?? 5, [proxyConfig])

  return (
    <div className="dashboard">
      {error && <div className="error-banner">{error}</div>}
      <div className="key-selector">
        <label>
          Клиентский ключ:{' '}
          <select
            value={selectedKey ?? ''}
            onChange={(event) => setSelectedKey(event.target.value)}
          >
            {clientKeys.map((key) => (
              <option key={key.id} value={key.name}>
                {key.name}
              </option>
            ))}
            {clientKeys.length === 0 && <option value="">нет активных ключей</option>}
          </select>
        </label>
      </div>
      <div className="cards-row">
        <WindowCard clientWindow={clientWindow} windowHours={windowHours} />
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
      {selectedKey && <WindowHistoryTable windows={windows} />}
    </div>
  )
}
