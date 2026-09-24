// Редактор пар «имя → значение» (extra-заголовки провайдера).
export default function KeyValueRows({
  title,
  rows,
  onChange,
}: {
  title: string
  rows: { key: string; value: string }[]
  onChange: (rows: { key: string; value: string }[]) => void
}) {
  const updateRow = (index: number, patch: Partial<{ key: string; value: string }>) => {
    onChange(rows.map((row, rowIndex) => (rowIndex === index ? { ...row, ...patch } : row)))
  }
  return (
    <div className="key-value-rows">
      <div className="key-value-title">{title}</div>
      {rows.map((row, index) => (
        <div key={index} className="key-value-row">
          <input
            type="text"
            placeholder="заголовок"
            value={row.key}
            onChange={(event) => updateRow(index, { key: event.target.value })}
          />
          <input
            type="text"
            placeholder="значение"
            value={row.value}
            onChange={(event) => updateRow(index, { value: event.target.value })}
          />
          <button
            type="button"
            className="button button-danger"
            onClick={() => onChange(rows.filter((_, rowIndex) => rowIndex !== index))}
          >
            ✕
          </button>
        </div>
      ))}
      <button
        type="button"
        className="button button-small"
        onClick={() => onChange([...rows, { key: '', value: '' }])}
      >
        + добавить заголовок
      </button>
    </div>
  )
}
