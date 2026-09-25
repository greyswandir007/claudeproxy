import { useEffect, useState } from 'react'
import ChatPage from './pages/ChatPage'
import DashboardPage from './pages/DashboardPage'
import ExposurePage from './pages/ExposurePage'
import KeysPage from './pages/KeysPage'
import ModelsPage from './pages/ModelsPage'

type Page = 'dashboard' | 'keys' | 'models' | 'exposure' | 'chat'

const REFRESH_INTERVAL_MILLISECONDS = 30_000

export default function App() {
  const [page, setPage] = useState<Page>('dashboard')
  const [refreshTick, setRefreshTick] = useState(0)
  const [updatedAt, setUpdatedAt] = useState<number>(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => {
      setRefreshTick((tick) => tick + 1)
      setUpdatedAt(Date.now())
    }, REFRESH_INTERVAL_MILLISECONDS)
    return () => clearInterval(timer)
  }, [])

  const refreshNow = () => {
    setRefreshTick((tick) => tick + 1)
    setUpdatedAt(Date.now())
  }

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-title">claudeproxy</div>
        <nav className="app-tabs">
          <button
            className={page === 'dashboard' ? 'tab tab-active' : 'tab'}
            onClick={() => setPage('dashboard')}
          >
            Дашборд
          </button>
          <button
            className={page === 'keys' ? 'tab tab-active' : 'tab'}
            onClick={() => setPage('keys')}
          >
            Ключи
          </button>
          <button
            className={page === 'models' ? 'tab tab-active' : 'tab'}
            onClick={() => setPage('models')}
          >
            Модели и провайдеры
          </button>
          <button
            className={page === 'exposure' ? 'tab tab-active' : 'tab'}
            onClick={() => setPage('exposure')}
          >
            Выдача моделей
          </button>
          <button
            className={page === 'chat' ? 'tab tab-active' : 'tab'}
            onClick={() => setPage('chat')}
          >
            Чат
          </button>
        </nav>
        <div className="app-refresh">
          <span className="muted">
            обновлено {new Date(updatedAt).toLocaleTimeString('ru-RU')}
          </span>
          <button className="button" onClick={refreshNow}>
            Обновить
          </button>
        </div>
      </header>
      <main>
        {page === 'dashboard' && <DashboardPage refreshTick={refreshTick} />}
        {page === 'keys' && <KeysPage refreshTick={refreshTick} />}
        {page === 'models' && <ModelsPage refreshTick={refreshTick} />}
        {page === 'exposure' && <ExposurePage refreshTick={refreshTick} />}
        {page === 'chat' && <ChatPage refreshTick={refreshTick} />}
      </main>
    </div>
  )
}
