import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { LatencyPointView } from '../api/client'

const CHART_HEIGHT = 220

// Линии p95 трёх метрик латентности (мс) по бакетам; тултип — среднее и p95.
export default function LatencyChart({
  points,
  isHourly,
}: {
  points: LatencyPointView[]
  isHourly: boolean
}) {
  const data = points.map((point) => ({
    label: isHourly
      ? new Date(point.bucketStartMilliseconds).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
      : new Date(point.bucketStartMilliseconds).toLocaleDateString([], { day: '2-digit', month: '2-digit' }),
    ttft: point.ttftPercentile95Milliseconds,
    upstream: point.upstreamPercentile95Milliseconds,
    duration: point.durationPercentile95Milliseconds,
    ttftMean: point.ttftMeanMilliseconds,
    upstreamMean: point.upstreamMeanMilliseconds,
    durationMean: point.durationMeanMilliseconds,
    requests: point.requests,
  }))
  return (
    <div className="latency-chart" style={{ width: '100%', height: CHART_HEIGHT }}>
      <ResponsiveContainer>
        <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 8 }}>
          <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
          <XAxis dataKey="label" stroke="var(--muted)" fontSize={12} tickLine={false} />
          <YAxis
            stroke="var(--muted)"
            fontSize={12}
            tickLine={false}
            width={56}
            tickFormatter={(value: number) => (value >= 1000 ? `${Math.round(value / 100) / 10}s` : `${Math.round(value)}ms`)}
          />
          <Tooltip
            contentStyle={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 8, color: 'var(--text)' }}
            formatter={(value, name) => {
              if (typeof value !== 'number') return [String(value ?? '—'), String(name)]
              const seconds = value >= 1000
              return [seconds ? `${Math.round(value / 100) / 10} с` : `${Math.round(value)} мс`, String(name)]
            }}
            labelFormatter={(_label, payload) => {
              const row = payload?.[0]?.payload as
                | { ttftMean?: number; upstreamMean?: number; durationMean?: number; requests?: number }
                | undefined
              if (!row) return String(_label)
              const meanPart = (value: number | undefined) =>
                value === undefined || value === null ? '—' : `${Math.round(value)} мс`
              return [
                String(_label),
                `запросов: ${row.requests ?? 0}`,
                `средние: ttft ${meanPart(row.ttftMean)} · провайдер ${meanPart(row.upstreamMean)} · всего ${meanPart(row.durationMean)}`,
              ].join(' · ')
            }}
          />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Line type="monotone" dataKey="ttft" name="ttft p95" stroke="#60a5fa" dot={false} strokeWidth={2} connectNulls />
          <Line type="monotone" dataKey="upstream" name="провайдер p95" stroke="#f59e0b" dot={false} strokeWidth={2} connectNulls />
          <Line type="monotone" dataKey="duration" name="полный ответ p95" stroke="#34d399" dot={false} strokeWidth={2} connectNulls />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
