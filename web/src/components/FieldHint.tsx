interface FieldHintProperties {
  /** Текст подсказки: выводится короткой строкой под полем. */
  text: string
}

/** Короткая серая строка-подсказка под полем формы (единый паттерн M32). */
export function FieldHint({ text }: FieldHintProperties) {
  return <span className="field-hint">{text}</span>
}

interface HintIconProperties {
  /** Текст всплывающей подсказки иконки. */
  text: string
}

/** Иконка (?) с CSS-тултипом — для плотных мест, где строка под полем мешает. */
export function HintIcon({ text }: HintIconProperties) {
  return (
    <span className="hint-icon" tabIndex={0} role="note">
      ?
      <span className="hint-icon-pop">{text}</span>
    </span>
  )
}
