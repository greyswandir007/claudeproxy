// Навигатор периодов дашборда: пресеты + произвольный диапазон.
export interface PeriodRange {
  fromMilliseconds: number
  toMilliseconds: number
}

interface Preset {
  id: string
  title: string
  /** Скользящий пресет: правая граница привязана к текущему моменту. */
  rolling: boolean
  /** Гранулярность таймлайна пресета — по номинальной длине, а не по прошедшей части. */
  bucket: 'hour' | 'day'
  range: () => PeriodRange
}

const DAY = 86_400_000

function dayStart(offsetDays: number): number {
  const date = new Date()
  date.setHours(0, 0, 0, 0)
  return date.getTime() - offsetDays * DAY
}

const PRESETS: Preset[] = [
  {
    id: 'today',
    title: 'Сегодня',
    rolling: true,
    bucket: 'hour',
    range: () => ({ fromMilliseconds: dayStart(0), toMilliseconds: Date.now() }),
  },
  {
    id: 'yesterday',
    title: 'Вчера',
    rolling: false,
    bucket: 'hour',
    range: () => ({
      fromMilliseconds: dayStart(1),
      toMilliseconds: dayStart(1) + DAY - 1,
    }),
  },
  {
    id: 'dayBeforeYesterday',
    title: 'Позавчера',
    rolling: false,
    bucket: 'hour',
    range: () => ({
      fromMilliseconds: dayStart(2),
      toMilliseconds: dayStart(2) + DAY - 1,
    }),
  },
  {
    id: 'week',
    title: 'Неделя',
    rolling: true,
    bucket: 'day',
    range: () => {
      const monday = new Date()
      monday.setHours(0, 0, 0, 0)
      const weekday = (monday.getDay() + 6) % 7 // понедельник = 0
      return { fromMilliseconds: monday.getTime() - weekday * DAY, toMilliseconds: Date.now() }
    },
  },
  {
    id: 'previousWeek',
    title: 'Прошлая неделя',
    rolling: false,
    bucket: 'day',
    range: () => {
      const monday = new Date()
      monday.setHours(0, 0, 0, 0)
      const weekday = (monday.getDay() + 6) % 7 // понедельник = 0
      const thisMonday = monday.getTime() - weekday * DAY
      return { fromMilliseconds: thisMonday - 7 * DAY, toMilliseconds: thisMonday - 1 }
    },
  },
  {
    id: 'last30days',
    title: '30 дней',
    rolling: true,
    bucket: 'day',
    range: () => ({ fromMilliseconds: Date.now() - 30 * DAY, toMilliseconds: Date.now() }),
  },
  {
    id: 'previousMonth',
    title: 'Прошлый месяц',
    rolling: false,
    bucket: 'day',
    range: () => {
      const now = new Date()
      const monthStart = new Date(now.getFullYear(), now.getMonth(), 1).getTime()
      return { fromMilliseconds: new Date(now.getFullYear(), now.getMonth() - 1, 1).getTime(), toMilliseconds: monthStart - 1 }
    },
  },
]

/**
 * Свежий диапазон скользящего пресета («сегодня», «7 дней», «30 дней»): правая
 * граница пересчитывается к текущему моменту при каждом вызове. Для фиксированных
 * пресетов и произвольного диапазона возвращает null.
 */
export function rollingPresetRange(presetId: string | null): PeriodRange | null {
  if (presetId == null) return null
  const preset = PRESETS.find((item) => item.id === presetId)
  if (preset == null || !preset.rolling) return null
  return preset.range()
}

/**
 * Гранулярность таймлайна пресета, заданная его номинальной длиной: у «недели»
 * это дни, даже если с начала недели прошло меньше двух суток. Для произвольного
 * диапазона возвращает null — там гранулярность выбирается по фактической длине.
 */
export function presetBucket(presetId: string | null): 'hour' | 'day' | null {
  if (presetId == null) return null
  return PRESETS.find((item) => item.id === presetId)?.bucket ?? null
}

export default function PeriodNavigator({
  selectedPreset,
  onSelectPreset,
  customRange,
  onCustomRange,
}: {
  selectedPreset: string
  onSelectPreset: (presetId: string, range: PeriodRange) => void
  customRange: PeriodRange
  onCustomRange: (range: PeriodRange) => void
}) {
  const toDateInput = (milliseconds: number) =>
    new Date(milliseconds).toISOString().slice(0, 10)
  const fromDateInput = (value: string, endOfDay: boolean) => {
    const parsed = new Date(`${value}T00:00:00`).getTime()
    return parsed + (endOfDay ? DAY - 1 : 0)
  }

  return (
    <div className="period-navigator">
      <div className="segmented period-presets">
        {PRESETS.map((preset) => (
          <button
            key={preset.id}
            className={selectedPreset === preset.id ? 'segment segment-active' : 'segment'}
            onClick={() => onSelectPreset(preset.id, preset.range())}
          >
            {preset.title}
          </button>
        ))}
        <button
          className={selectedPreset === 'custom' ? 'segment segment-active' : 'segment'}
          onClick={() => onCustomRange(customRange)}
        >
          Произвольный
        </button>
      </div>
      {selectedPreset === 'custom' && (
        <div className="custom-range">
          <input
            type="date"
            value={toDateInput(customRange.fromMilliseconds)}
            max={toDateInput(customRange.toMilliseconds)}
            onChange={(event) =>
              onCustomRange({ ...customRange, fromMilliseconds: fromDateInput(event.target.value, false) })
            }
          />
          <span className="muted">—</span>
          <input
            type="date"
            value={toDateInput(customRange.toMilliseconds)}
            min={toDateInput(customRange.fromMilliseconds)}
            onChange={(event) =>
              onCustomRange({ ...customRange, toMilliseconds: fromDateInput(event.target.value, true) })
            }
          />
        </div>
      )}
    </div>
  )
}
