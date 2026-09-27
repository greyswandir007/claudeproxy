import { useEffect, useMemo, useState } from 'react'
import {
  api,
  type ClientKey,
  type FallbackReport,
  type GroupedTimelinePoint,
  type GroupedUsage,
  type LatencyStatisticsView,
  type ProviderCost,
  type ProviderLimitUsage,
  type ProviderWindowSummary,
  type ProxyConfig,
  type RangeSummary,
  type RouteCooldown,
  type TimelinePoint,
  type WindowBoundary,
  type ProviderWindowBoundary,
  type WindowSummary,
} from '../api/client'
import PeriodNavigator, { rollingPresetRange, type PeriodRange } from '../components/PeriodNavigator'
import PeriodCard from '../components/PeriodCard'
import CostSection from '../components/CostSection'
import LatencyChart from '../components/LatencyChart'
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
  const [latencyStatistics, setLatencyStatistics] = useState<LatencyStatisticsView | null>(null)

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

  // Скользящие пресеты («сегодня», «7 дней», «30 дней») пересчитывают правую
  // границу к текущему моменту на каждом такте обновления: иначе новые корзины
  // попадают за правую границу зафиксированного диапазона, и график «замирает»
  // до перезагрузки страницы. refreshTick в зависимостях именно поэтому.
  const effectiveRange = useMemo(
    () => rollingPresetRange(selectedPreset) ?? periodRange,
    [selectedPreset, periodRange, refreshTick],
  )

  const [timelineRows, setTimelineRows] = useState<Record<string, number | string>[]>([])
  const [timelineLabels, setTimelineLabels] = useState<
    { key: string; label: string; color: string }[]
  >([])
  const [boundaries, setBoundaries] = useState<WindowBoundary[]>([])
  const [providerBoundaries, setProviderBoundaries] = useState<ProviderWindowBoundary[]>([])
  const [error, setError] = useState<string | null>(null)

  const keyParameter = selectedKey !== null && selectedKey.length > 0 ? selectedKey : null
  const windowHours = useMemo(() => proxyConfig?.windowHours ?? 5, [proxyConfig])
  const bucket: 'hour' | 'day' =
    effectiveRange.toMilliseconds - effectiveRange.fromMilliseconds <= 2 * DAY ? 'hour' : 'day'

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
    const from = effectiveRange.fromMilliseconds
    const to = effectiveRange.toMilliseconds
    const timelineRequest =
      sliceMode === 'total'
        ? api
            .timelineRange(bucket, keyParameter, from, to)
            .then((points: TimelinePoint[]) => {
              setTimelineRows(
                fillTimelineGaps(
                  points.map((point) => ({
                    sortKey: point.bucketStartMilliseconds,
                    label: formatBucketLabel(point.bucketStartMilliseconds, bucket),
                    inputTokens: point.inputTokens,
                    outputTokens: point.outputTokens,
                    cacheReadTokens: point.cacheReadTokens,
                    cacheCreationTokens: point.cacheCreationTokens,
                  })),
                  from,
                  to,
                  bucket,
                ),
              )
              setTimelineLabels(TOTAL_SERIES.map((series) => ({ ...series })))
            })
        : api
            .groupedTimeline(bucket, keyParameter, from, to, sliceMode)
            .then((points: GroupedTimelinePoint[]) => {
              const pivoted = pivotGroupedTimeline(points, bucket)
              setTimelineRows(
                fillTimelineGaps(pivoted.data, from, to, bucket, pivoted.labels.map((entry) => entry.key)),
              )
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
    // дорожки окон провайдеров — независимо от выбранного ключа
    if (bucket === 'hour') {
      api
        .providerWindowBoundaries(from, to)
        .then(setProviderBoundaries)
        .catch(() => setProviderBoundaries([]))
    } else {
      setProviderBoundaries([])
    }
    api
      .latency(bucket, from, to)
      .then(setLatencyStatistics)
      .catch(() => setLatencyStatistics(null))
  }, [refreshTick, effectiveRange, bucket, sliceMode, selectedKey])

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
      </div>
      <TimelineChart
        data={timelineRows}
        labels={timelineLabels}
        bucket={bucket}
        boundaries={boundaries}
        providerWindows={providerBoundaries}
        fromMilliseconds={effectiveRange.fromMilliseconds}
        toMilliseconds={effectiveRange.toMilliseconds}
        keySelected={keyParameter !== ''}
      />
      {latencyStatistics && latencyStatistics.requests > 0 && (
        <div className="latency-section">
          <div className="latency-tiles">
            <div className="latency-tile">
              <div className="latency-tile-caption">время до первого токена</div>
              <div className="latency-tile-value">{formatLatency(latencyStatistics.ttftMeanMilliseconds)}</div>
              <div className="muted">p95 {formatLatency(latencyStatistics.ttftPercentile95Milliseconds)}</div>
            </div>
            <div className="latency-tile">
              <div className="latency-tile-caption">ответ провайдера</div>
              <div className="latency-tile-value">{formatLatency(latencyStatistics.upstreamMeanMilliseconds)}</div>
              <div className="muted">p95 {formatLatency(latencyStatistics.upstreamPercentile95Milliseconds)}</div>
            </div>
            <div className="latency-tile">
              <div className="latency-tile-caption">полный ответ клиенту</div>
              <div className="latency-tile-value">{formatLatency(latencyStatistics.durationMeanMilliseconds)}</div>
              <div className="muted">p95 {formatLatency(latencyStatistics.durationPercentile95Milliseconds)}</div>
            </div>
            <div className="latency-tile">
              <div className="latency-tile-caption">запросов в периоде</div>
              <div className="latency-tile-value">{latencyStatistics.requests}</div>
              <div className="muted">2xx, включая кэш</div>
            </div>
          </div>
          <LatencyChart points={latencyStatistics.points} isHourly={bucket === 'hour'} />
        </div>
      )}
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

/**
 * Полная шкала бакетов: 24 часа для дня, 7 дней для недели и т.д. —
 * недостающие точки заполняются нулями, чтобы график был ровным.
 * total-режим: нули по фиксированным ключам; срезы — по динамическим
 * seriesKeys (передаются из pivotGroupedTimeline).
 */
function fillTimelineGaps(
  rows: Record<string, number | string>[],
  from: number,
  to: number,
  bucket: 'hour' | 'day',
  seriesKeys?: string[],
): Record<string, number | string>[] {
  const bucketSize = bucket === 'hour' ? 3_600_000 : 86_400_000
  const rowByBucket = new Map<number, Record<string, number | string>>()
  for (const row of rows) {
    rowByBucket.set(Number(row.sortKey), row)
  }
  const totalKeys = ['inputTokens', 'outputTokens', 'cacheReadTokens', 'cacheCreationTokens']
  const zeroKeys = seriesKeys ?? totalKeys
  const filled: Record<string, number | string>[] = []
  for (
    let bucketStart = Math.floor(from / bucketSize) * bucketSize;
    bucketStart <= to;
    bucketStart += bucketSize
  ) {
    const existing = rowByBucket.get(bucketStart)
    if (existing) {
      filled.push(existing)
      continue
    }
    const gapRow: Record<string, number | string> = {
      sortKey: bucketStart,
      label: formatBucketLabel(bucketStart, bucket),
    }
    for (const key of zeroKeys) {
      gapRow[key] = 0
    }
    filled.push(gapRow)
  }
  return filled
}

function formatBucketLabel(bucketStart: number, bucket: 'hour' | 'day'): string {
  return bucket === 'hour'
    ? new Date(bucketStart).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
    : new Date(bucketStart).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })
}

function formatLatency(milliseconds: number | null): string {
  if (milliseconds === null || milliseconds === undefined) return '—'
  if (milliseconds >= 1000) {
    const seconds = Math.round(milliseconds / 100) / 10
    return `${seconds.toLocaleString('ru-RU')} с`
  }
  return `${Math.round(milliseconds)} мс`
}
