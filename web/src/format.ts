// Форматирование чисел и дат для дашборда (ru-RU).

const numberFormatter = new Intl.NumberFormat('ru-RU')

export function formatTokens(value: number): string {
  if (Math.abs(value) >= 1_000_000) {
    return `${(value / 1_000_000).toLocaleString('ru-RU', { maximumFractionDigits: 2 })} млн`
  }
  return numberFormatter.format(value)
}

export function formatDateTime(milliseconds: number): string {
  return new Date(milliseconds).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatTime(milliseconds: number): string {
  return new Date(milliseconds).toLocaleTimeString('ru-RU', {
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatDay(milliseconds: number): string {
  return new Date(milliseconds).toLocaleDateString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
  })
}

export function formatRemaining(milliseconds: number): string {
  const totalMinutes = Math.max(0, Math.floor(milliseconds / 60_000))
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours <= 0) return `${minutes} мин`
  return `${hours} ч ${minutes.toString().padStart(2, '0')} мин`
}

export function totalTokens(totals: {
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
}): number {
  return (
    totals.inputTokens + totals.outputTokens + totals.cacheCreationTokens + totals.cacheReadTokens
  )
}
