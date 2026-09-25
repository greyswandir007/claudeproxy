import { useState } from 'react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type {
  GroupedTimelinePoint,
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

const GRIDLINE = '#2c2c2a'
const MUTED_INK = '#898781'

interface TooltipEntry {
  name?: string
  value?: number | string
  color?: string
}

function ChartTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean
  payload?: TooltipEntry[]
  label?: string
}) {
  if (!active || !payload || payload.length === 0) return null
  return (
    <div className="chart-tooltip">
      <div className="chart-tooltip-title">{label}</div>
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
}: {
  data: Record<string, number | string>[]
  labels: { key: string; label: string; color: string }[]
  bucket: 'hour' | 'day'
  boundaries: WindowBoundary[]
}) {
  const [hoveredSeries, setHoveredSeries] = useState<string | null>(null)
  const [isolatedSeries, setIsolatedSeries] = useState<string | null>(null)
  const focus = isolatedSeries ?? hoveredSeries
  // активная серия: 1 — фокус, DIMMED — остальные
  const opacityOf = (key: string) => (focus === null || focus === key ? 1 : 0.15)

  if (data.length === 0) {
    return (
      <section className="card chart-card">
        <h2>Расход по времени</h2>
        <p className="muted">Нет данных за выбранный период.</p>
      </section>
    )
  }
  return (
    <section className="card chart-card">
      <h2>
        Расход по времени
        {bucket === 'hour' && boundaries.length > 0 && (
          <span className="card-note">серые линии — старт 5-часовых окон ключа</span>
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
        <ResponsiveContainer width="100%" height={280}>
          <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
            <CartesianGrid stroke={GRIDLINE} vertical={false} />
            <XAxis
              dataKey="label"
              tick={{ fill: MUTED_INK, fontSize: 12 }}
              axisLine={{ stroke: GRIDLINE }}
              tickLine={false}
              minTickGap={48}
            />
            <YAxis
              tick={{ fill: MUTED_INK, fontSize: 12 }}
              axisLine={false}
              tickLine={false}
              width={64}
              tickFormatter={(value: number) => formatTokens(value)}
            />
            <Tooltip content={<ChartTooltip />} />
            {bucket === 'hour' &&
              boundaries.map((boundary) => (
                <ReferenceLine
                  key={boundary.startedAtMilliseconds}
                  x={formatTime(boundary.startedAtMilliseconds)}
                  stroke="#898781"
                  strokeDasharray="4 4"
                  strokeWidth={1}
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

export { TOTAL_SERIES }
