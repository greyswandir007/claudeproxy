import { useEffect, useRef, useState } from 'react'
import { api, chatApi, type ChatMessage, type ClientKey } from '../api/client'

// Веб-чат: диалог через сам прокси от имени выбранного клиентского ключа,
// история на ключ, стриминг ответа, сброс и переименование треда.
export default function ChatPage({ refreshTick }: { refreshTick: number }) {
  const [clientKeys, setClientKeys] = useState<ClientKey[]>([])
  const [selectedKey, setSelectedKey] = useState('')
  const [model, setModel] = useState('')
  const [models, setModels] = useState<string[]>([])
  const [title, setTitle] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streamingText, setStreamingText] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api
      .keys()
      .then((keys) => {
        const activeKeys = keys.filter((key) => key.revokedAt === null)
        setClientKeys(activeKeys)
        setSelectedKey((current) => current || activeKeys[0]?.name || '')
      })
      .catch((loadError: Error) => setError(loadError.message))
    api
      .config()
      .then((config) => {
        setModels(config.exposedModels)
        setModel((current) => current || config.exposedModels[0] || '')
      })
      .catch(() => setModels([]))
  }, [refreshTick])

  useEffect(() => {
    if (selectedKey.length === 0) return
    chatApi
      .state(selectedKey)
      .then((state) => {
        setTitle(state.thread.title)
        setMessages(state.messages)
        setError(null)
      })
      .catch((loadError: Error) => setError(loadError.message))
  }, [selectedKey, refreshTick])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, streamingText])

  const send = async () => {
    const content = input.trim()
    if (content.length === 0 || streaming || selectedKey.length === 0 || model.length === 0) return
    setInput('')
    setStreaming(true)
    setStreamingText('')
    setError(null)
    setMessages((current) => [
      ...current,
      { id: -Date.now(), role: 'user', content, createdAt: Date.now() },
    ])
    let accumulated = ''
    await chatApi.send(
      selectedKey,
      model,
      content,
      (chunk) => {
        accumulated += chunk
        setStreamingText(accumulated)
      },
      () => undefined,
      (errorMessage) => setError(errorMessage),
    )
    setStreaming(false)
    setStreamingText('')
    if (accumulated.length > 0) {
      setMessages((current) => [
        ...current,
        { id: -Date.now() - 1, role: 'assistant', content: accumulated, createdAt: Date.now() },
      ])
    }
  }

  const reset = async () => {
    if (!window.confirm('Очистить историю чата этого ключа?')) return
    await chatApi.clear(selectedKey)
    setMessages([])
  }

  const saveTitle = async () => {
    const trimmed = title.trim()
    if (trimmed.length === 0) return
    await chatApi.renameThread(selectedKey, trimmed)
  }

  return (
    <div className="chat-page">
      {error && <div className="error-banner">{error}</div>}
      <div className="chat-toolbar">
        <select value={selectedKey} onChange={(event) => setSelectedKey(event.target.value)}>
          {clientKeys.map((key) => (
            <option key={key.id} value={key.name}>
              {key.name}
            </option>
          ))}
        </select>
        <select value={model} onChange={(event) => setModel(event.target.value)}>
          {models.map((modelId) => (
            <option key={modelId} value={modelId}>
              {modelId}
            </option>
          ))}
        </select>
        <input
          type="text"
          className="chat-title"
          value={title}
          placeholder="название треда"
          onChange={(event) => setTitle(event.target.value)}
          onBlur={saveTitle}
        />
        <button className="button button-small" onClick={reset}>
          Сбросить историю
        </button>
      </div>
      <div className="chat-messages">
        {messages.length === 0 && !streaming && (
          <p className="muted">История пуста — задайте вопрос модели от имени ключа «{selectedKey}».</p>
        )}
        {messages.map((message) => (
          <div
            key={message.id}
            className={message.role === 'user' ? 'chat-bubble chat-user' : 'chat-bubble chat-assistant'}
          >
            {message.content}
          </div>
        ))}
        {streaming && (
          <div className="chat-bubble chat-assistant chat-streaming">
            {streamingText.length > 0 ? streamingText : '…'}
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>
      <div className="chat-input">
        <textarea
          value={input}
          placeholder="Сообщение (Enter — отправить, Shift+Enter — перенос)"
          rows={2}
          onChange={(event) => setInput(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              void send()
            }
          }}
        />
        <button className="button" disabled={streaming || input.trim().length === 0} onClick={send}>
          {streaming ? 'Отвечает…' : 'Отправить'}
        </button>
      </div>
    </div>
  )
}
