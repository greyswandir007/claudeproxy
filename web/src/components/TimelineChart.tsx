import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { TimelinePoint } from '../api/client'
import { formatDay, formatTime, formatTokens } from '../format'

// Палитра (тёмная тема, валидирована: CVD ΔE 8.4, контраст ≥ 3:1).
// Цвет закреплён за серией, порядок фиксированный.
const SERIES = [
  { key: 'inputTokens', label: 'Вход', color: '#3987e5' },
  { key: 'outputTokens', label: 'Выход', color: '#d95926' },
  { key: 'cacheReadTokens', label: 'Кэш-чтение', color: '#199e70' },
  { key: 'cacheCreationTokens', label: 'Кэш-запись', color: '#c98500' },
] as const

const SURFACE = '#1a1a19'
const MUTED_INK = '#898781'
const GRIDLINE = '#2c2c2a'

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
          <span className="chart-tooltip-value">
            {formatTokens(Number(entry.value ?? 0))}
          </span>
        </div>
      ))}
    </div>
  )
}

// Stacked-area таймлайн расхода токенов по часам/дням.
export default function TimelineChart({
  points,
  bucket,
}: {
  points: TimelinePoint[]
  bucket: 'hour' | 'day'
}) {
  const data = points.map((point) => ({
    ...point,
    label:
      bucket === 'hour'
        ? formatTime(point.bucketStartMilliseconds)
        : formatDay(point.bucketStartMilliseconds),
  }))
  return (
    <section className="card chart-card">
      <h2>Расход по времени</h2>
      {data.length === 0 ? (
        <p className="muted">Пока нет данных за период.</p>
      ) : (
        <div className="chart-container">
          <ResponsiveContainer width="100%" height={280}>
            <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
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
              <Legend
                wrapperStyle={{ color: '#c3c2b7', fontSize: 13 }}
                iconType="square"
                iconSize={10}
              />
              {SERIES.map((series) => (
                <Area
                  key={series.key}
                  type="monotone"
                  dataKey={series.key}
                  name={series.label}
                  stackId="tokens"
                  stroke={SURFACE}
                  strokeWidth={2}
                  fill={series.color}
                  fillOpacity={0.85}
                  isAnimationActive={false}
                />
              ))}
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  )
}
