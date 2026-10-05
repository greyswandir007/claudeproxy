import { useState } from 'react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceArea,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type {
  GroupedTimelinePoint,
  ProviderWindowBoundary,
  WindowBoundary,
} from '../api/client'
import { formatDay, formatTime, formatTokens } from '../format'

// Палитра срез-серий (тёмная тема; первые 4 — из валидированной категорийной,
// далее — дополнительные оттенки, «прочее» — серый).
const GROUPED_COLORS = [
  '#3987e5',
  '#d95926',
  '#199e70',
  '#c98500',
  '#d55181',
  '#9085e9',
  '#e66767',
  '#898781',
]

// Серии общего графика: цвет закреплён за серией, фиксированный порядок.
const TOTAL_SERIES = [
  { key: 'inputTokens', label: 'Вход', color: '#3987e5' },
  { key: 'outputTokens', label: 'Выход', color: '#d95926' },
  { key: 'cacheReadTokens', label: 'Кэш-чтение', color: '#199e70' },
  { key: 'cacheCreationTokens', label: 'Кэш-запись', color: '#c98500' },
] as const

// Серии среза «Экономия»: потрачено/сэкономлено/всего.
const SAVINGS_SERIES = [
  { key: 'totalTokens', label: 'Всего (потрачено + сэкономлено)', color: '#3987e5' },
  { key: 'spentTokens', label: 'Потрачено', color: '#d95926' },
  { key: 'savedTokens', label: 'Сэкономлено', color: '#199e70' },
] as const

const GRIDLINE = '#2c2c2a'
const MUTED_INK = '#898781'

// Цвета дорожек окон провайдеров над графиком (по порядку появления провайдера).
const PROVIDER_LANE_COLORS = ['#9085e9', '#d55181', '#e66767', '#c98500', '#199e70']

interface TooltipEntry {
  name?: string
  value?: number | string
  color?: string
}

function ChartTooltip({
  active,
  payload,
  label,
  bucket,
}: {
  active?: boolean
  payload?: TooltipEntry[]
  label?: string | number
  bucket: 'hour' | 'day'
}) {
  if (!active || !payload || payload.length === 0) return null
  // ось времени числовая: метка тултипа — начало корзины в миллисекундах
  const bucketStart = Number(label)
  return (
    <div className="chart-tooltip">
      <div className="chart-tooltip-title">
        {bucket === 'hour' ? formatTime(bucketStart) : formatDay(bucketStart)}
      </div>
      {payload.map((entry) => (
        <div key={entry.name} className="chart-tooltip-row">
          <span className="chart-tooltip-swatch" style={{ background: entry.color }} />
          <span>{entry.name}</span>
          <span className="chart-tooltip-value">{formatTokens(Number(entry.value ?? 0))}</span>
        </div>
      ))}
    </div>
  )
}

/**
 * Таймлайн расхода: линии без заливки; наведение/клик по легенде подсвечивает
 * одну серию и затемняет остальные. На часовом виде — границы 5-часовых окон.
 */
export default function TimelineChart({
  data,
  labels,
  bucket,
  boundaries,
  providerWindows,
  fromMilliseconds,
  toMilliseconds,
  keySelected,
}: {
  data: Record<string, number | string>[]
  labels: { key: string; label: string; color: string }[]
  bucket: 'hour' | 'day'
  boundaries: WindowBoundary[]
  providerWindows: ProviderWindowBoundary[]
  /** Диапазон графика — для позиционирования дорожек окон провайдеров. */
  fromMilliseconds: number
  toMilliseconds: number
  keySelected: boolean
}) {
  const [hoveredSeries, setHoveredSeries] = useState<string | null>(null)
  const [isolatedSeries, setIsolatedSeries] = useState<string | null>(null)
  const focus = isolatedSeries ?? hoveredSeries
  // активная серия: 1 — фокус, DIMMED — остальные
  const opacityOf = (key: string) => (focus === null || focus === key ? 1 : 0.15)

  // дорожки окон провайдеров: строка на провайдера, окна — цветные отрезки диапазона
  const providerLanes = (() => {
    const lanesByName = new Map<string, ProviderWindowBoundary[]>()
    for (const window of providerWindows) {
      const lane = lanesByName.get(window.providerName) ?? []
      lane.push(window)
      lanesByName.set(window.providerName, lane)
    }
    return [...lanesByName.entries()].map(([providerName, windows], index) => ({
      providerName,
      color: PROVIDER_LANE_COLORS[index % PROVIDER_LANE_COLORS.length],
      windows,
    }))
  })()

  // числовая ось времени: точки — начала корзин, по краям полкорзины воздуха;
  // деления — границы корзин, не чаще ~12, чтобы подписи не слипались
  const bucketMilliseconds = bucket === 'hour' ? 3_600_000 : 86_400_000
  const tickStride = Math.max(1, Math.ceil(data.length / 12))
  const timeTicks = data
    .filter((_, index) => index % tickStride === 0)
    .map((row) => Number(row.sortKey))

  if (data.length === 0) {
    return (
      <section className="card chart-card">
        <h2>Расход по времени</h2>
        <p className="muted">Нет данных за выбранный период.</p>
      </section>
    )
  }

  // окна: полоса от старта до конца (конец текущего окна обрезаем краем графика);
  // подписи времени на границах — только когда корзин немного (период «сутки»)
  const chartEnd = Number(data[data.length - 1].sortKey) + bucketMilliseconds / 2
  const showWindowLabels = data.length <= 40
  return (
    <section className="card chart-card">
      <h2>
        Расход по времени
        {bucket === 'hour' && boundaries.length > 0 && (
          <span className="card-note">
            серые полосы — 5-часовые окна ключа, пунктир — старт, точки — конец
          </span>
        )}
        {bucket === 'hour' && providerLanes.length > 0 && (
          <span className="card-note">цветные дорожки — 5-часовые окна провайдеров</span>
        )}
        {bucket === 'hour' && boundaries.length === 0 && !keySelected && (
          <span className="card-note">5-часовые окна — выберите конкретный ключ</span>
        )}
      </h2>
      <div className="chart-container">
        <div className="chart-legend">
          {labels.map((series) => (
            <span
              key={series.key}
              className={
                focus === series.key
                  ? 'chart-legend-item chart-legend-focus'
                  : focus === null
                    ? 'chart-legend-item'
                    : 'chart-legend-item chart-legend-dimmed'
              }
              onMouseEnter={() => setHoveredSeries(series.key)}
              onMouseLeave={() => setHoveredSeries(null)}
              onClick={() =>
                setIsolatedSeries((current) => (current === series.key ? null : series.key))
              }
              title="клик — показать только эту серию"
            >
              <span className="chart-legend-swatch" style={{ background: series.color }} />
              {series.label}
            </span>
          ))}
        </div>
        {bucket === 'hour' && providerLanes.length > 0 && (
          <div className="provider-lanes">
            {providerLanes.map((lane) => (
              <div className="provider-lane" key={lane.providerName}>
                <span className="provider-lane-name" title={lane.providerName}>
                  {lane.providerName}
                </span>
                <div className="provider-lane-track">
                  {lane.windows.map((window, windowIndex) => {
                    const clipFrom = Math.max(window.startedAtMilliseconds, fromMilliseconds)
                    const clipTo = Math.min(window.endsAtMilliseconds, toMilliseconds)
                    if (clipTo <= clipFrom) return null
                    const leftPercent =
                      ((clipFrom - fromMilliseconds) / (toMilliseconds - fromMilliseconds)) * 100
                    const widthPercent =
                      ((clipTo - clipFrom) / (toMilliseconds - fromMilliseconds)) * 100
                    return (
                      <div
                        key={windowIndex}
                        className="provider-lane-segment"
                        style={{
                          left: `${leftPercent}%`,
                          width: `${widthPercent}%`,
                          background: lane.color,
                        }}
                        title={`${lane.providerName}: окно ${formatTime(clipFrom)} — ${formatTime(clipTo)}`}
                      />
                    )
                  })}
                </div>
              </div>
            ))}
          </div>
        )}
        <ResponsiveContainer width="100%" height={280}>
          <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
            <CartesianGrid stroke={GRIDLINE} vertical={false} />
            <XAxis
              dataKey="sortKey"
              type="number"
              domain={[
                (dataMin: number) => dataMin - bucketMilliseconds / 2,
                (dataMax: number) => dataMax + bucketMilliseconds / 2,
              ]}
              ticks={timeTicks}
              tickFormatter={(bucketStart: number) =>
                bucket === 'hour' ? formatTime(bucketStart) : formatDay(bucketStart)
              }
              tick={{ fill: MUTED_INK, fontSize: 12 }}
              axisLine={{ stroke: GRIDLINE }}
              tickLine={false}
            />
            <YAxis
              tick={{ fill: MUTED_INK, fontSize: 12 }}
              axisLine={false}
              tickLine={false}
              width={64}
              tickFormatter={(value: number) => formatTokens(value)}
            />
            <Tooltip content={<ChartTooltip bucket={bucket} />} />
            {bucket === 'hour' &&
              boundaries.map((boundary) => {
                const windowEnd = Math.min(boundary.endsAtMilliseconds, chartEnd)
                if (boundary.startedAtMilliseconds >= windowEnd) {
                  return null
                }
                return (
                  <ReferenceArea
                    key={`window-area-${boundary.startedAtMilliseconds}`}
                    x1={boundary.startedAtMilliseconds}
                    x2={windowEnd}
                    fill="#898781"
                    fillOpacity={0.12}
                  />
                )
              })}
            {bucket === 'hour' &&
              boundaries.map((boundary) => (
                <ReferenceLine
                  key={`window-start-${boundary.startedAtMilliseconds}`}
                  x={boundary.startedAtMilliseconds}
                  stroke="#898781"
                  strokeDasharray="4 4"
                  strokeWidth={1}
                  label={
                    showWindowLabels
                      ? {
                          value: formatTime(boundary.startedAtMilliseconds),
                          position: 'insideTopLeft',
                          fill: MUTED_INK,
                          fontSize: 10,
                        }
                      : undefined
                  }
                />
              ))}
            {bucket === 'hour' &&
              boundaries
                .filter((boundary) => boundary.endsAtMilliseconds <= chartEnd)
                .map((boundary) => (
                  <ReferenceLine
                    key={`window-end-${boundary.startedAtMilliseconds}`}
                    x={boundary.endsAtMilliseconds}
                    stroke="#898781"
                    strokeDasharray="1 3"
                    strokeWidth={1}
                    label={
                      showWindowLabels
                        ? {
                            value: formatTime(boundary.endsAtMilliseconds),
                            position: 'insideTopRight',
                            fill: MUTED_INK,
                            fontSize: 10,
                          }
                        : undefined
                    }
                  />
                ))}
            {labels.map((series) => (
              <Line
                key={series.key}
                type="monotone"
                dataKey={series.key}
                name={series.label}
                stroke={series.color}
                strokeWidth={2}
                strokeOpacity={opacityOf(series.key)}
                dot={false}
                activeDot={
                  focus === null || focus === series.key
                    ? { r: 4, strokeWidth: 0 }
                    : { r: 0 }
                }
                isAnimationActive={false}
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </div>
    </section>
  )
}

/** Пивот срез-точек в wide-формат для Recharts + список серий с цветами. */
export function pivotGroupedTimeline(
  grouped: GroupedTimelinePoint[],
  bucket: 'hour' | 'day',
): { data: Record<string, number | string>[]; labels: { key: string; label: string; color: string }[] } {
  const totalsByName = new Map<string, number>()
  for (const point of grouped) {
    totalsByName.set(point.label, (totalsByName.get(point.label) ?? 0) + point.tokens)
  }
  const orderedLabels = [...totalsByName.entries()]
    .sort((first, second) => second[1] - first[1])
    .map(([name]) => name)
  // «прочее» всегда последним и серым
  const otherIndex = orderedLabels.indexOf('прочее')
  if (otherIndex >= 0) {
    orderedLabels.splice(otherIndex, 1)
    orderedLabels.push('прочее')
  }
  const colorOf = (label: string, index: number) =>
    label === 'прочее' ? '#898781' : GROUPED_COLORS[index % GROUPED_COLORS.length]
  const series = orderedLabels.map((label, index) => ({
    key: label,
    label,
    color: colorOf(label, index),
  }))
  const byBucket = new Map<number, Record<string, number | string>>()
  for (const point of grouped) {
    const row =
      byBucket.get(point.bucketStartMilliseconds) ??
      (() => {
        const created: Record<string, number | string> = {
          bucketStartMilliseconds: point.bucketStartMilliseconds,
          sortKey: point.bucketStartMilliseconds,
          label:
            bucket === 'hour'
              ? formatTime(point.bucketStartMilliseconds)
              : formatDay(point.bucketStartMilliseconds),
        }
        for (const name of orderedLabels) created[name] = 0
        byBucket.set(point.bucketStartMilliseconds, created)
        return created
      })()
    row[point.label] = point.tokens
  }
  const data = [...byBucket.values()].sort(
    (first, second) => Number(first.sortKey) - Number(second.sortKey),
  )
  return { data, labels: series }
}

export { TOTAL_SERIES, SAVINGS_SERIES }
