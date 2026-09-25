import { useEffect, useMemo, useState } from 'react'
import {
  api,
  type ClientKey,
  type FallbackReport,
  type GroupedTimelinePoint,
  type GroupedUsage,
  type ProviderCost,
  type ProviderLimitUsage,
  type ProviderWindowSummary,
  type ProxyConfig,
  type RangeSummary,
  type RouteCooldown,
  type TimelinePoint,
  type WindowBoundary,
  type WindowSummary,
} from '../api/client'
import PeriodNavigator, { type PeriodRange } from '../components/PeriodNavigator'
import PeriodCard from '../components/PeriodCard'
import CostSection from '../components/CostSection'
import ProviderLimitBars from '../components/ProviderLimitBars'
import RoutingHealth from '../components/RoutingHealth'
import TimelineChart, { TOTAL_SERIES, pivotGroupedTimeline } from '../components/TimelineChart'
import UsageTable from '../components/UsageTable'
import WindowCard from '../components/WindowCard'
import WindowHistoryTable from '../components/WindowHistoryTable'

/** Срез таймлайна. */
type SliceMode = 'total' | 'model' | 'provider'

const DAY = 86_400_000

export default function DashboardPage({ refreshTick }: { refreshTick: number }) {
  const [clientKeys, setClientKeys] = useState<ClientKey[]>([])
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [proxyConfig, setProxyConfig] = useState<ProxyConfig | null>(null)
  const [providerLimits, setProviderLimits] = useState<ProviderLimitUsage[]>([])
  const [clientWindow, setClientWindow] = useState<WindowSummary | null>(null)
  const [summaries, setSummaries] = useState<RangeSummary[]>([])
  const [byModelRowsWeek, setByModelRowsWeek] = useState<GroupedUsage[]>([])
  const [byModelRowsMonth, setByModelRowsMonth] = useState<GroupedUsage[]>([])
  const [byProviderRowsWeek, setByProviderRowsWeek] = useState<GroupedUsage[]>([])
  const [byProviderRowsMonth, setByProviderRowsMonth] = useState<GroupedUsage[]>([])
  const [windows, setWindows] = useState<WindowSummary[]>([])
  const [providerWindows, setProviderWindows] = useState<ProviderWindowSummary[]>([])
  const [windowModels, setWindowModels] = useState<GroupedUsage[]>([])
  const [windowProviders, setWindowProviders] = useState<GroupedUsage[]>([])
  const [fallbackReport, setFallbackReport] = useState<FallbackReport | null>(null)
  const [routeCooldowns, setRouteCooldowns] = useState<RouteCooldown[]>([])
  const [providerCosts, setProviderCosts] = useState<ProviderCost[]>([])

  // навигация по периодам
  const [selectedPreset, setSelectedPreset] = useState('last7days')
  const [periodRange, setPeriodRange] = useState<PeriodRange>(() => ({
    fromMilliseconds: Date.now() - 7 * DAY,
    toMilliseconds: Date.now(),
  }))
  const [customRange, setCustomRange] = useState<PeriodRange>(() => ({
    fromMilliseconds: dayStart(1),
    toMilliseconds: Date.now(),
  }))
  const [sliceMode, setSliceMode] = useState<SliceMode>('total')

  const [timelineRows, setTimelineRows] = useState<Record<string, number | string>[]>([])
  const [timelineLabels, setTimelineLabels] = useState<
    { key: string; label: string; color: string }[]
  >([])
  const [boundaries, setBoundaries] = useState<WindowBoundary[]>([])
  const [error, setError] = useState<string | null>(null)

  const keyParameter = selectedKey !== null && selectedKey.length > 0 ? selectedKey : null
  const windowHours = useMemo(() => proxyConfig?.windowHours ?? 5, [proxyConfig])
  const bucket: 'hour' | 'day' =
    periodRange.toMilliseconds - periodRange.fromMilliseconds <= 2 * DAY ? 'hour' : 'day'

  useEffect(() => {
    api
      .keys()
      .then((keys) => {
        const activeKeys = keys.filter((key) => key.revokedAt === null)
        setClientKeys(activeKeys)
        setSelectedKey((current) => {
          if (current !== null) return current
          const mostActive = [...activeKeys].sort(
            (first, second) =>
              (second.lastUsedAt ?? second.createdAt) - (first.lastUsedAt ?? first.createdAt),
          )[0]
          return mostActive?.name ?? ''
        })
      })
      .catch((loadError: Error) => setError(loadError.message))
    api
      .config()
      .then(setProxyConfig)
      .catch(() => setProxyConfig(null))
    api
      .providerLimitUsage()
      .then(setProviderLimits)
      .catch(() => setProviderLimits([]))
    api
      .fallbackReport('7d', null)
      .then(setFallbackReport)
      .catch(() => setFallbackReport(null))
    api
      .routeCooldowns()
      .then(setRouteCooldowns)
      .catch(() => setRouteCooldowns([]))
    api
      .providerWindowHistory()
      .then(setProviderWindows)
      .catch(() => setProviderWindows([]))
    api
      .providerCosts()
      .then(setProviderCosts)
      .catch(() => setProviderCosts([]))
  }, [refreshTick])

  // сводки и окна выбранного ключа
  useEffect(() => {
    if (selectedKey === null) return
    const summariesRequest = Promise.all([
      api.summary('today', keyParameter),
      api.summary('7d', keyParameter),
      api.summary('30d', keyParameter),
    ])
    const tablesRequest = Promise.all([
      api.byModel('7d', keyParameter),
      api.byModel('30d', keyParameter),
      api.byProvider('7d', keyParameter),
      api.byProvider('30d', keyParameter),
    ])
    const windowsRequest: Promise<[WindowSummary | null, WindowSummary[]]> = keyParameter
      ? Promise.all([api.currentWindow(keyParameter), api.windowHistory(keyParameter)])
      : Promise.resolve([null, []])
    Promise.all([summariesRequest, tablesRequest, windowsRequest])
      .then(
        ([
          [todaySummary, weekSummary, monthSummary],
          [modelsWeek, modelsMonth, providersWeek, providersMonth],
          [currentWindow, windowHistory],
        ]) => {
          setSummaries([todaySummary, weekSummary, monthSummary])
          setByModelRowsWeek(modelsWeek)
          setByModelRowsMonth(modelsMonth)
          setByProviderRowsWeek(providersWeek)
          setByProviderRowsMonth(providersMonth)
          setClientWindow(currentWindow)
          setWindows(windowHistory)
          setError(null)
        },
      )
      .catch((loadError: Error) => setError(loadError.message))

    // срез внутри текущего окна ключа: по моделям и провайдерам
    if (keyParameter) {
      api
        .currentWindow(keyParameter)
        .then((currentWindow) => {
          if (!currentWindow) {
            setWindowModels([])
            setWindowProviders([])
            return
          }
          const now = Date.now()
          return Promise.all([
            api.byModelRange(keyParameter, currentWindow.startedAtMilliseconds, now),
            api.byProviderRange(keyParameter, currentWindow.startedAtMilliseconds, now),
          ]).then(([models, providers]) => {
            setWindowModels(models)
            setWindowProviders(providers)
          })
        })
        .catch(() => {
          setWindowModels([])
          setWindowProviders([])
        })
    }
  }, [refreshTick, selectedKey])

  // таймлайн выбранного периода/среза + границы окон
  useEffect(() => {
    const from = periodRange.fromMilliseconds
    const to = periodRange.toMilliseconds
    const timelineRequest =
      sliceMode === 'total'
        ? api
            .timelineRange(bucket, keyParameter, from, to)
            .then((points: TimelinePoint[]) => {
              setTimelineRows(
                points.map((point) => ({
                  sortKey: point.bucketStartMilliseconds,
                  label:
                    bucket === 'hour'
                      ? new Date(point.bucketStartMilliseconds).toLocaleTimeString('ru-RU', {
                          hour: '2-digit',
                          minute: '2-digit',
                        })
                      : new Date(point.bucketStartMilliseconds).toLocaleDateString('ru-RU', {
                          day: '2-digit',
                          month: '2-digit',
                        }),
                  inputTokens: point.inputTokens,
                  outputTokens: point.outputTokens,
                  cacheReadTokens: point.cacheReadTokens,
                  cacheCreationTokens: point.cacheCreationTokens,
                })),
              )
              setTimelineLabels(TOTAL_SERIES.map((series) => ({ ...series })))
            })
        : api
            .groupedTimeline(bucket, keyParameter, from, to, sliceMode)
            .then((points: GroupedTimelinePoint[]) => {
              const pivoted = pivotGroupedTimeline(points, bucket)
              setTimelineRows(pivoted.data)
              setTimelineLabels(pivoted.labels)
            })
    timelineRequest.catch((loadError: Error) => setError(loadError.message))

    if (bucket === 'hour' && keyParameter) {
      api
        .windowBoundaries(keyParameter, from, to)
        .then(setBoundaries)
        .catch(() => setBoundaries([]))
    } else {
      setBoundaries([])
    }
  }, [refreshTick, periodRange, bucket, sliceMode, selectedKey])

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
            <option value="">Все ключи (сводно)</option>
            {clientKeys.map((key) => (
              <option key={key.id} value={key.name}>
                {key.name}
                {key.lastUsedAt ? '' : ' (без обращений)'}
              </option>
            ))}
          </select>
        </label>
      </div>
      <WindowCard
        clientWindow={clientWindow}
        windowHours={windowHours}
        windowModels={windowModels}
        windowProviders={windowProviders}
        providerLimits={providerLimits}
      />
      <div className="cards-row">
        {summaries.map((summary) => (
          <PeriodCard key={summary.range} summary={summary} />
        ))}
      </div>
      <PeriodNavigator
        selectedPreset={selectedPreset}
        onSelectPreset={(presetId, range) => {
          setSelectedPreset(presetId)
          setPeriodRange(range)
        }}
        customRange={customRange}
        onCustomRange={(range) => {
          setCustomRange(range)
          setSelectedPreset('custom')
          setPeriodRange(range)
        }}
      />
      <div className="chart-controls">
        <div className="segmented">
          {(
            [
              ['total', 'Общее'],
              ['model', 'По моделям'],
              ['provider', 'По провайдерам'],
            ] as [SliceMode, string][]
          ).map(([mode, title]) => (
            <button
              key={mode}
              className={sliceMode === mode ? 'segment segment-active' : 'segment'}
              onClick={() => setSliceMode(mode)}
            >
              {title}
            </button>
          ))}
        </div>
        <span className="muted">гранулярность: {bucket === 'hour' ? 'часы' : 'дни'} (авто)</span>
      </div>
      <TimelineChart
        data={timelineRows}
        labels={timelineLabels}
        bucket={bucket}
        boundaries={boundaries}
      />
      <div className="tables-row">
        <UsageTable
          title="По моделям"
          rowsWeek={byModelRowsWeek}
          rowsMonth={byModelRowsMonth}
          labelTitle="Модель"
        />
        <UsageTable
          title="По провайдерам"
          rowsWeek={byProviderRowsWeek}
          rowsMonth={byProviderRowsMonth}
          labelTitle="Провайдер"
        />
      </div>
      <RoutingHealth report={fallbackReport} cooldowns={routeCooldowns} />
      <CostSection providerCosts={providerCosts} />
      {providerLimits.length > 0 && (
        <section className="card dashboard-limits">
          <h2>
            Лимиты провайдеров
            <span className="card-note">информационные; графики моделей — относительно лимита</span>
          </h2>
          {providerLimits.map((limitUsage) => (
            <div key={limitUsage.providerName} className="dashboard-limit-provider">
              <h3>{limitUsage.providerName}</h3>
              <ProviderLimitBars key={limitUsage.providerName} usage={limitUsage} />
            </div>
          ))}
        </section>
      )}
      {keyParameter && <WindowHistoryTable windows={windows} providerWindows={providerWindows} />}
    </div>
  )
}

function dayStart(offsetDays: number): number {
  const date = new Date()
  date.setHours(0, 0, 0, 0)
  return date.getTime() - offsetDays * DAY
}
