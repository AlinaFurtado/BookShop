import { useEffect, useRef, useState } from 'react'
import { api, catalogQuery } from './api'
import Icon from './components/Icon'
import Bookshelf from './components/Bookshelf'
import BookCard from './components/BookCard'
import BookModal from './components/BookModal'
import AuthModal from './components/AuthModal'
import PipPanel from './components/PipPanel'
import LibraryModal from './components/LibraryModal'
import PassportCollection from './components/PassportCollection'
import PassportModal from './components/PassportModal'
import TrendingSection from './components/TrendingSection'
import { GENRES } from './bookData'
import { looksLikeReadingRequest } from './searchIntent'
import './App.css'

function Filters({ prefix, genre, minRating, onGenre, onRating, disabled }) {
  return <div className="filter-fields"><label htmlFor={`${prefix}-genre`}>Genre<select id={`${prefix}-genre`} value={genre} onChange={(e) => onGenre(e.target.value)} disabled={disabled}><option value="">Any genre</option>{GENRES.map((item) => <option key={item}>{item}</option>)}</select></label><label htmlFor={`${prefix}-rating`}>Reader rating<select id={`${prefix}-rating`} value={minRating} onChange={(e) => onRating(e.target.value)} disabled={disabled}><option value="">Any rating</option><option value="3.5">3.5+ stars</option><option value="4">4+ stars</option><option value="4.5">4.5+ stars</option></select></label></div>
}

function browsePath(filters, page = 1, cursor = null) {
  const query = new URLSearchParams(catalogQuery(filters).slice(1))
  query.set('page', page)
  if (cursor) query.set('cursor', cursor)
  return `/api/books/browse?${query}`
}

function App() {
  const [user, setUser] = useState(null)
  const [sessionLoading, setSessionLoading] = useState(true)
  const [sessionError, setSessionError] = useState('')
  const [shelf, setShelf] = useState([])
  const [shelfLoading, setShelfLoading] = useState(false)
  const [shelfError, setShelfError] = useState('')
  const [books, setBooks] = useState([])
  const [view, setView] = useState('shelf')
  const [title, setTitle] = useState('')
  const [searchOverride, setSearchOverride] = useState(null)
  const [searchIncludeOlder, setSearchIncludeOlder] = useState(false)
  const [genre, setGenre] = useState('')
  const [minRating, setMinRating] = useState('')
  const [loadingKind, setLoadingKind] = useState('featured')
  const [resultError, setResultError] = useState('')
  const [resultMeta, setResultMeta] = useState({ title: 'Fresh chapters for your shelf', subtitle: 'English editions of books first published in the last five years. Search a title or author to explore older books.', mode: 'featured' })
  const [pageMeta, setPageMeta] = useState({ page: 1, total: 0, hasMore: false, source: '', message: '' })
  const [selectedBook, setSelectedBook] = useState(null)
  const [authMode, setAuthMode] = useState(null)
  const [libraryOpen, setLibraryOpen] = useState(false)
  const [passportId, setPassportId] = useState(null)
  const [pendingKey, setPendingKey] = useState('')
  const [toast, setToast] = useState('')
  const [aiStatus, setAiStatus] = useState(null)
  const [aiStatusLoading, setAiStatusLoading] = useState(true)
  const [aiLoading, setAiLoading] = useState(false)
  const [aiResult, setAiResult] = useState(null)
  const [aiError, setAiError] = useState('')
  const [aiFeedback, setAiFeedback] = useState('')
  const [aiSubmitted, setAiSubmitted] = useState(null)
  const [pipResetKey, setPipResetKey] = useState(0)
  const resultRequest = useRef({ sequence: 0, controller: null })
  const aiRequest = useRef({ sequence: 0, controller: null, running: false })
  const mutationLock = useRef(false)
  const pendingAction = useRef(null)
  const discoverRef = useRef(null)
  const trendingRef = useRef(null)
  const aiResultsRef = useRef(null)
  const toastTimer = useRef(null)
  const sessionGeneration = useRef(0)
  const successfulQuery = useRef(null)
  const readingRequestDetected = looksLikeReadingRequest(title)
  const searchMode = searchOverride || (readingRequestDetected ? 'ai' : 'catalog')

  // Independent startup reads. A guest can browse; only signed-in readers
  // have a personal shelf. Abort on unmount (also safe in React StrictMode).
  useEffect(() => {
    const controller = new AbortController()
    const activeResultRequest = resultRequest.current
    const activeAiRequest = aiRequest.current
    let alive = true
    async function initializeAccount() {
      try {
        const account = await api('/api/auth/me', { signal: controller.signal })
        if (!alive) return
        setUser(account)
        setShelfLoading(true)
        const generation = sessionGeneration.current
        api('/api/shelf', { signal: controller.signal }).then((entries) => { if (alive && generation === sessionGeneration.current) setShelf(entries) })
          .catch((err) => { if (alive && generation === sessionGeneration.current) setShelfError(err.message) })
          .finally(() => { if (alive && generation === sessionGeneration.current) setShelfLoading(false) })
      } catch (error) { if (alive && error.status !== 401) setSessionError(error.message) }
      finally { if (alive) setSessionLoading(false) }
    }
    async function initializeCatalog() {
      try {
        const catalog = await api(browsePath({}), { signal: controller.signal, timeout: 35000 })
        if (alive && resultRequest.current.sequence === 0) {
          setBooks(catalog.books)
          setPageMeta(catalog)
          successfulQuery.current = { title: '', genre: '', minRating: '' }
        }
      } catch (error) { if (alive && resultRequest.current.sequence === 0) setResultError(error.message) }
      finally { if (alive && resultRequest.current.sequence === 0) setLoadingKind('') }
    }
    async function initializeRecommendations() {
      try {
        const status = await api('/api/recommendations/status', { signal: controller.signal })
        if (alive) setAiStatus(status)
      } catch {
        if (alive) setAiStatus({ available: false, message: 'Pip’s AI recommendation desk is unavailable right now. You can still browse by genre.' })
      } finally { if (alive) setAiStatusLoading(false) }
    }
    // Signing in and opening a saved shelf do not wait for the external catalog.
    initializeAccount()
    initializeCatalog()
    initializeRecommendations()
    return () => { alive = false; controller.abort(); clearTimeout(toastTimer.current); activeResultRequest.controller?.abort(); activeAiRequest.sequence += 1; activeAiRequest.running = false; activeAiRequest.controller?.abort() }
  }, [])

  function notify(message) {
    clearTimeout(toastTimer.current)
    setToast(message)
    toastTimer.current = setTimeout(() => setToast(''), 5000)
  }

  function openAuth(mode = 'register', action = null) {
    pendingAction.current = action
    setSelectedBook(null)
    setAuthMode(mode)
  }

  function openPassport(entry) {
    setView('passport')
    setSelectedBook(null)
    setLibraryOpen(false)
    setPassportId(entry.id)
  }

  async function savePassport(id, fields, finishDate) {
    if (mutationLock.current) throw new Error('Another change is still saving. Please try again in a moment.')
    mutationLock.current = true
    setPendingKey(`passport:${id}`)
    try {
      let saved = await api(`/api/shelf/${id}/passport`, { method: 'PATCH', body: fields })
      const notesEntry = saved
      setShelf((current) => current.map((entry) => entry.id === id ? notesEntry : entry))
      // Each successful response stays in state if the later date save fails.
      if (finishDate !== undefined && finishDate !== (saved.finishDate || '')) {
        saved = await api(`/api/shelf/${id}/status`, { method: 'PATCH', body: { status: 'READ', ...(finishDate ? { finishDate } : { clearFinishDate: true }) } })
        const datedEntry = saved
        setShelf((current) => current.map((entry) => entry.id === id ? datedEntry : entry))
      }
      notify('Your private passport notes are safely tucked away.')
      return saved
    } finally { mutationLock.current = false; setPendingKey('') }
  }

  async function refreshShelf() {
    const generation = sessionGeneration.current
    setShelfLoading(true); setShelfError('')
    try { const entries = await api('/api/shelf'); if (generation === sessionGeneration.current) setShelf(entries) }
    catch (err) { if (generation === sessionGeneration.current) setShelfError(err.message) }
    finally { if (generation === sessionGeneration.current) setShelfLoading(false) }
  }

  async function authenticated(account) {
    sessionGeneration.current += 1
    setUser(account); setSessionError(''); setAuthMode(null)
    await refreshShelf()
    const action = pendingAction.current
    pendingAction.current = null
    if (action?.type === 'add') await performAdd(action.book, action.status, action.finishDate)
    else if (action?.type === 'review') setSelectedBook(action.book)
    else notify(`Welcome to your shelf, ${account.name.split(' ')[0]}!`)
  }

  async function signOut() {
    if (mutationLock.current) return
    mutationLock.current = true; setPendingKey('logout')
    try {
      await api('/api/auth/logout', { method: 'POST' })
      sessionGeneration.current += 1
      setShelfLoading(false)
      setUser(null); setShelf([]); setSelectedBook(null); setShelfError('')
      aiRequest.current.sequence += 1; aiRequest.current.controller?.abort(); aiRequest.current.running = false
      setAiLoading(false); setAiResult(null); setAiError(''); setAiFeedback('')
      setAiSubmitted(null); setTitle(''); setSearchOverride(null); setSearchIncludeOlder(false)
      setPipResetKey((current) => current + 1)
      setLibraryOpen(false)
      setPassportId(null)
      notify('Signed out. Your books will be here when you return.')
    } catch (err) { notify(err.message) }
    finally { mutationLock.current = false; setPendingKey('') }
  }

  // Catalog book.id is a provider key. The server returns a saved
  // entry with a numeric database id, used for later PATCH and DELETE requests.
  async function performAdd(book, status = 'WANT_TO_READ', finishDate = null) {
    const workId = book.workId || book.id
    if (mutationLock.current || shelf.some((entry) => entry.workId === workId)) return
    mutationLock.current = true; setPendingKey(`add:${workId}`)
    try {
      let saved = await api('/api/shelf', { method: 'POST', body: { ...book, id: workId, status, finishDate } })
      if (saved.status !== status) saved = await api(`/api/shelf/${saved.id}/status`, { method: 'PATCH', body: { status, finishDate } })
      setShelf((current) => [...current.filter((entry) => entry.workId !== saved.workId), saved])
      if (saved.status === 'READ') openPassport(saved)
      notify(`“${book.title}” is tucked into ${status === 'READ' ? 'Read' : status === 'READING' ? 'Reading' : 'Want to Read'}.`)
    } catch (err) {
      if (err.status === 401) { setUser(null); setShelf([]); openAuth('login', { type: 'add', book, status, finishDate }) }
      else notify(err.message)
    } finally { mutationLock.current = false; setPendingKey('') }
  }

  function addBook(book, status = 'WANT_TO_READ', finishDate = null) {
    if (sessionLoading) return
    if (!user) { openAuth('register', { type: 'add', book, status, finishDate }); return }
    performAdd(book, status, finishDate)
  }

  async function updateStatus(id, status, finishDate, clearFinishDate = false) {
    const previous = shelf.find((entry) => entry.id === id)
    if (mutationLock.current || (previous?.status === status && finishDate === undefined && !clearFinishDate)) return
    mutationLock.current = true; setPendingKey(`entry:${id}`)
    try {
      const saved = await api(`/api/shelf/${id}/status`, { method: 'PATCH', body: { status, ...(finishDate !== undefined ? { finishDate } : {}), ...(clearFinishDate ? { clearFinishDate: true } : {}) } })
      // map replaces one entry without mutating React's current array.
      setShelf((current) => current.map((entry) => entry.id === id ? saved : entry))
      if (saved.status === 'READ' && previous?.status !== 'READ') openPassport(saved)
      notify(`Moved to ${status === 'READ' ? 'Read. Another chapter conquered!' : status === 'READING' ? 'Reading. Happy page-turning!' : 'Want to Read.'}`)
    } catch (err) { notify(err.message) }
    finally { mutationLock.current = false; setPendingKey('') }
  }

  async function removeBook(id) {
    if (mutationLock.current) return
    mutationLock.current = true; setPendingKey(`entry:${id}`)
    try {
      await api(`/api/shelf/${id}`, { method: 'DELETE' })
      setShelf((current) => current.filter((entry) => entry.id !== id)); setSelectedBook(null)
      notify('Book removed. There’s always room for another story.')
    } catch (err) { notify(err.message) }
    finally { mutationLock.current = false; setPendingKey('') }
  }

  function scrollToDiscover() {
    requestAnimationFrame(() => discoverRef.current?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' }))
  }

  function showDiscover() { setView('discover'); scrollToDiscover() }

  function showTrending() {
    setView('trending')
    requestAnimationFrame(() => trendingRef.current?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' }))
  }

  // Cancel superseded requests; an older slow response must never replace the
  // newer query. The sequence guard is useful even when cancellation is late.
  async function loadResults(kind, { surprise = false, filters = null, append = false } = {}) {
    if (append && (!successfulQuery.current || !pageMeta.hasMore || loadingKind)) return
    if (surprise) { setSearchOverride(null); setTitle('') }
    if (aiRequest.current.running) cancelRecommendations()
    resultRequest.current.controller?.abort()
    const controller = new AbortController()
    const sequence = ++resultRequest.current.sequence
    resultRequest.current.controller = controller
    setLoadingKind(kind); setResultError('')
    try {
      // Pagination uses the last successful query, never unsubmitted input.
      const selectedFilters = append ? successfulQuery.current : { title: kind === 'search' ? title.trim() : '', ...(filters || { genre, minRating }) }
      // Surprise picks always come from the general audience, even after Kids.
      if (surprise && selectedFilters.genre === 'Kids') {
        selectedFilters.genre = ''
        setGenre('')
      }
      const queryTitle = selectedFilters.title || ''
      const response = await api(browsePath(selectedFilters, append ? pageMeta.page + 1 : 1, append ? pageMeta.nextCursor : null), { signal: controller.signal, timeout: 35000 })
      if (append && response.source !== pageMeta.source) throw new Error('The catalog connection changed while loading more. Run your search again to browse a fresh set of results.')
      const nextBooks = surprise && response.books.length ? [response.books[Math.floor(Math.random() * response.books.length)]] : response.books
      const publicationLabel = response.minPublicationYear ? `${response.minPublicationYear}–${response.maxPublicationYear}` : ''
      const metadata = surprise
        ? { title: 'A wild book appears!', subtitle: `A recent pick, first published ${publicationLabel}. Children’s books stay in Kids.`, mode: 'surprise' }
        : queryTitle.trim()
          ? { title: `The search for “${queryTitle.trim()}”`, subtitle: 'Matching titles from every publication year, with your choice of genre and reader rating.', mode: 'search' }
          : { title: selectedFilters.genre === 'Kids' ? 'Little readers. Big adventures.' : selectedFilters.genre ? `A little ${selectedFilters.genre.toLowerCase()} for your shelf` : selectedFilters.minRating ? 'Well-loved recent books, waiting for you' : 'Fresh chapters for your shelf', subtitle: `English editions. First published ${publicationLabel}. Search a title or author to include older books.`, mode: 'featured' }
      if (sequence !== resultRequest.current.sequence) return
      setPageMeta({ ...response, total: surprise ? nextBooks.length : response.total, hasMore: !surprise && response.hasMore })
      successfulQuery.current = selectedFilters
      if (append) {
        // Open Library pages can overlap. Work ids prevent duplicate cards.
        setBooks((current) => [...new Map([...current, ...nextBooks].map((book) => [book.id, book])).values()])
      } else {
        setBooks(nextBooks || [])
        setResultMeta(metadata)
        scrollToDiscover()
      }
    } catch (err) {
      if (!controller.signal.aborted && sequence === resultRequest.current.sequence) setResultError(err.message)
    } finally { if (sequence === resultRequest.current.sequence) setLoadingKind('') }
  }

  function cancelResults() {
    resultRequest.current.sequence += 1; resultRequest.current.controller?.abort(); setLoadingKind('')
    notify('Search stopped. Your previous picks are still here.')
  }

  function focusAiResults() {
    requestAnimationFrame(() => {
      aiResultsRef.current?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' })
      aiResultsRef.current?.focus({ preventScroll: true })
    })
  }

  async function recommendBooks({ prompt, includeOlder }) {
    if (aiRequest.current.running) return
    setSearchOverride('ai')
    setTitle(prompt); setSearchIncludeOlder(!!includeOlder)
    resultRequest.current.sequence += 1; resultRequest.current.controller?.abort(); setLoadingKind('')
    setAiError(''); setAiFeedback(''); setAiResult(null); setAiSubmitted(null)
    focusAiResults()
    if (sessionLoading || aiStatusLoading) { setAiFeedback('Checking your session and Pip’s availability. Please submit again in a moment.'); return }
    if (!aiStatus?.available) { setAiError(aiStatus?.message || 'AI recommendations are unavailable right now. Try Title or author search.'); return }
    if (prompt.length < 10 || prompt.length > 600) { setAiError('Use 10–600 characters to describe the book you want.'); return }
    if (!user) { setAiFeedback('Sign in, then press Ask Pip again to send this reading request.'); openAuth('login'); return }
    const controller = new AbortController()
    const sequence = ++aiRequest.current.sequence
    const generation = sessionGeneration.current
    aiRequest.current.controller = controller
    aiRequest.current.running = true
    setAiSubmitted({ prompt, genre, minRating, includeOlder: !!includeOlder })
    setAiLoading(true); setAiError(''); setAiFeedback(''); setAiResult(null)
    focusAiResults()
    try {
      const result = await api('/api/recommendations', { method: 'POST', signal: controller.signal, timeout: 55000, body: { prompt, genre, minRating: minRating ? Number(minRating) : null, includeOlder } })
      if (sequence !== aiRequest.current.sequence || generation !== sessionGeneration.current) return
      setAiResult(result)
      focusAiResults()
    } catch (error) {
      if (controller.signal.aborted || sequence !== aiRequest.current.sequence || generation !== sessionGeneration.current) return
      if (error.status === 401) {
        setUser(null); setShelf([])
        setAiError('Your session ended. Sign in, then submit this reading request again when you are ready.')
        openAuth('login')
      } else setAiError(error.message)
    } finally {
      if (sequence === aiRequest.current.sequence) { aiRequest.current.running = false; setAiLoading(false) }
    }
  }

  function cancelRecommendations() {
    aiRequest.current.sequence += 1; aiRequest.current.controller?.abort(); aiRequest.current.running = false
    setAiLoading(false); setAiError('')
    setAiFeedback('Stopped waiting for these recommendations. A request already sent to the AI service may still finish and count toward its allowance.')
  }

  function chooseSearchMode(mode) {
    setSearchOverride(mode)
    if (mode === 'ai') {
      resultRequest.current.sequence += 1; resultRequest.current.controller?.abort(); setLoadingKind('')
    } else if (aiRequest.current.running) cancelRecommendations()
  }

  function updateDiscoveryFilters(nextGenre, nextRating) {
    setGenre(nextGenre); setMinRating(nextRating)
    // Changing AI filters never sends a model request or the draft as a title.
    // Catalog filters apply to the last submitted query, not unsubmitted text.
    if (searchMode === 'ai') return
    const submittedTitle = successfulQuery.current?.title || ''
    loadResults(submittedTitle ? 'search' : 'featured', { filters: { title: submittedTitle, genre: nextGenre, minRating: nextRating } })
  }

  const loadedCountLabel = pageMeta.total == null
    ? `${books.length.toLocaleString()} ${books.length === 1 ? 'book' : 'books'} shown`
    : `${books.length.toLocaleString()} of ${Number(pageMeta.total).toLocaleString()} books`
  const selectedEntry = selectedBook ? shelf.find((entry) => entry.workId === (selectedBook.workId || selectedBook.id)) : null
  const passportEntry = shelf.find((entry) => entry.id === passportId && entry.status === 'READ')
  const firstName = user?.name?.split(' ')[0] || 'reader'
  const currentlyReading = shelf.filter((book) => book.status === 'READING').length
  const readCount = shelf.filter((book) => book.status === 'READ').length

  return <>
    <a className="skip-link" href="#main-content">Skip to content</a>
    <div className="app-shell">
      <header className="site-header">
        <button className="brand" onClick={() => { setView('shelf'); window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' }) }} aria-label="Book Buddy home"><span className="brand-mark"><Icon name="book" size={25} /></span><span>book<span className="purple">buddy</span><sup>✦</sup><small>A PLACE FOR YOUR NEXT CHAPTER</small></span></button>
        <nav className="main-nav" aria-label="Main navigation"><button aria-current={view === 'shelf' ? 'page' : undefined} onClick={() => setView('shelf')}><Icon name="book" size={16} />My shelf</button><button aria-current={view === 'discover' ? 'page' : undefined} onClick={showDiscover}><Icon name="spark" size={16} />Discover</button><button aria-current={view === 'trending' ? 'page' : undefined} onClick={showTrending}><Icon name="trending" size={16} />Trending now</button><button aria-current={view === 'passport' ? 'page' : undefined} onClick={() => setView('passport')}><Icon name="book" size={16} />Reading Passport</button></nav>
        <div className="account-area">{sessionLoading ? <span className="session-loading">One moment…</span> : user ? <><div className="account-greeting"><span className="avatar" aria-hidden="true">{firstName.slice(0, 1).toUpperCase()}</span><span>Hey, <strong>{firstName}</strong></span></div><button className="text-button signout" onClick={signOut} disabled={!!pendingKey}>{pendingKey === 'logout' ? 'Signing out…' : 'Sign out'}</button></> : <><button className="text-button login-link" onClick={() => openAuth('login')}>Sign in</button><button className="button button-lime header-join" onClick={() => openAuth('register')}>My own shelf <Icon name="arrow" size={15} /></button></>}</div>
      </header>

      <main id="main-content">
        {sessionError && <div className="banner-error" role="alert">{sessionError}<button className="text-button" onClick={() => window.location.reload()}>Reconnect</button></div>}
        <section className="hero" aria-labelledby="hero-title"><div className="hero-copy"><div className="hero-kicker"><span />FOR THE ONE-MORE-CHAPTER PEOPLE</div><h1 id="hero-title">A bookshelf with<br /><em>main character energy.</em><span className="hero-underline" aria-hidden="true" /></h1><p>Your reading life, a little more magical. Find your next favorite,<br className="desktop-break" /> keep the books you love, and let your curiosity get weird.</p><div className="hero-bottom"><span className="handwritten">Less scrolling. More storytelling.</span><svg className="hand-arrow" viewBox="0 0 65 35" aria-hidden="true"><path d="M2 6C20 32 42 32 59 14M45 14l15-2-2 15" /></svg></div></div><div className="hero-art" aria-hidden="true"><span className="art-spark art-spark-one">✦</span><div className="hero-sticker"><Icon name="book" size={46} /><span>BOOKED<br />& UNBOTHERED</span></div><span className="art-spark art-spark-two">✧</span><span className="art-caption">a very good place<br />to lose the plot.</span></div></section>

        <div className="main-grid"><div className="shelf-column">{view === 'passport' ? <PassportCollection entries={shelf} user={user} loading={shelfLoading || sessionLoading} error={shelfError} onRetry={refreshShelf} onOpen={openPassport} onAuth={() => openAuth('login')} onShelf={() => setView('shelf')} /> : view === 'shelf' ? <Bookshelf user={user} entries={shelf} loading={shelfLoading || sessionLoading} error={shelfError} onOpen={setSelectedBook} onAuth={() => openAuth('register')} onDiscover={showDiscover} onRetry={refreshShelf} onViewAll={() => setLibraryOpen(true)} /> : <section className="panel discovery-intro"><span className="eyebrow">FOLLOW YOUR CURIOSITY</span><h2>Your next favorite is<br /><em>out there somewhere.</em></h2><p>A rainy-day mystery? Dragons with found-family feelings? A love story that ruins your sleep schedule? There’s a book for that.</p><div className="discovery-quote"><span aria-hidden="true">“</span>A rainy Sunday,<br />but with dragons.<Icon name="spark" size={30} /></div><button className="button button-purple" onClick={scrollToDiscover}>Find your next favorite <Icon name="arrow" size={17} /></button><p className="small-muted">Or search a title in the catalog below.</p></section>}
          <div className="reading-strip"><span className="strip-icon"><Icon name="coffee" size={21} /></span><p>{user ? <><strong>{currentlyReading ? `${currentlyReading} ${currentlyReading === 1 ? 'world' : 'worlds'} open right now.` : 'No reading deadlines here.'}</strong> {readCount ? `${readCount} ${readCount === 1 ? 'book' : 'books'} finished. Every chapter counts.` : 'Read what you love. Love what you read.'}</> : <><strong>A library that grows with you.</strong> No reading streaks. No guilt. Just good books.</>}</p><span aria-hidden="true">✦</span></div>
        </div>

          <PipPanel key={pipResetKey} busy={!!loadingKind} aiStatus={aiStatus} aiStatusLoading={aiStatusLoading} aiBusy={aiLoading} sessionLoading={sessionLoading} signedIn={!!user} filtersLabel={`${genre || 'Any genre'} · ${minRating ? `${minRating}+ stars` : 'Any rating'}`} onRecommend={recommendBooks} onCancelRecommend={cancelRecommendations} onMood={(nextGenre) => {
            setGenre(nextGenre)
            setTitle(''); setSearchOverride(null)
            loadResults('featured', { filters: { genre: nextGenre, minRating } })
          }} />
        </div>

        <TrendingSection sectionRef={trendingRef} shelf={shelf} pendingKey={pendingKey} locked={!!pendingKey || sessionLoading} onOpen={setSelectedBook} onAdd={addBook} onStatus={updateStatus} />

        <section className="panel discover-panel" ref={discoverRef} id="discover" aria-labelledby="results-title"><div className="discover-heading"><div><span className="eyebrow">LITTLE DOORS INTO NEW WORLDS</span><h2 id="results-title">{searchMode === 'ai' ? 'Tell us your next chapter' : resultMeta.title}<span className="purple"> ✦</span></h2><p className="results-subtitle" aria-live="polite">{searchMode === 'ai' ? 'Describe a reading mood. Pip will look for real books that fit.' : resultMeta.subtitle}</p></div><button className="button surprise-button" onClick={() => loadResults('featured', { surprise: true })} disabled={!!loadingKind}><Icon name="dice" size={17} />Surprise me</button></div>
          <form className={`catalog-search ${searchMode === 'ai' ? 'search-is-ai' : ''}`} onSubmit={(event) => {
            event.preventDefault()
            if (searchMode === 'ai') recommendBooks({ prompt: title.trim(), includeOlder: searchIncludeOlder })
            else if (title.trim().length > 160) setResultError('Title or author searches are limited to 160 characters. Choose Describe a book for a longer reading request.')
            else loadResults(title.trim() ? 'search' : 'featured')
          }}>
            <div className="search-mode-picker" role="group" aria-label="Search type">
              <button type="button" aria-pressed={searchMode === 'catalog'} onClick={() => chooseSearchMode('catalog')}><Icon name="search" size={15} />Title or author</button>
              <button type="button" aria-pressed={searchMode === 'ai'} onClick={() => chooseSearchMode('ai')}><Icon name="spark" size={15} />Describe a book · AI</button>
            </div>
            <label className="search-field" htmlFor="book-search"><Icon name={searchMode === 'ai' ? 'spark' : 'search'} size={19} /><span className="sr-only">{searchMode === 'ai' ? 'Describe the book you want to read' : 'Search by book title or author'}</span><input id="book-search" type="search" value={title} disabled={aiLoading} onChange={(event) => { setTitle(event.target.value); if (!event.target.value.trim()) setSearchOverride(null) }} maxLength={600} minLength={searchMode === 'ai' ? 10 : undefined} required={searchMode === 'ai'} placeholder={searchMode === 'ai' ? 'I need a fantasy book with dragons…' : 'Search a title or author, or describe your next read…'} aria-describedby="search-route-help" /></label>
            <button className={`button ${searchMode === 'ai' ? 'button-purple' : 'button-coral'} search-submit`} disabled={searchMode === 'ai' ? aiLoading || sessionLoading || aiStatusLoading : !!loadingKind} type="submit">{searchMode === 'ai' ? aiLoading ? <><span className="spinner" />Asking Pip…</> : <>{user ? 'Ask Pip' : 'Sign in to ask Pip'}<Icon name="spark" size={16} /></> : <>Find a book<Icon name="arrow" size={17} /></>}</button>
            <p id="search-route-help" className="search-route-help" role="status">{searchMode === 'ai' ? <>{!searchOverride && readingRequestDetected ? 'Reading request detected. ' : ''}<strong>AI recommendations</strong> match your description to real catalog books. {aiStatusLoading ? 'Checking availability…' : !aiStatus?.available ? aiStatus?.message || 'AI is unavailable right now.' : !user ? 'Sign in, then submit this request.' : 'Ready when you press Ask Pip.'}</> : <>Searches book titles and authors. For a reading mood or a request, choose <strong>Describe a book · AI</strong>.</>}</p>
            <div className="catalog-filters"><Filters prefix="catalog" genre={genre} minRating={minRating} onGenre={(next) => updateDiscoveryFilters(next, minRating)} onRating={(next) => updateDiscoveryFilters(genre, next)} disabled={searchMode === 'ai' ? aiLoading : !!loadingKind} /><button className="text-button reset-filters" type="button" onClick={() => updateDiscoveryFilters('', '')} disabled={!genre && !minRating}>Reset</button></div>
            {searchMode === 'ai' && <div className="main-ai-options"><label><input type="checkbox" checked={searchIncludeOlder} disabled={aiLoading} onChange={(event) => setSearchIncludeOlder(event.target.checked)} />Include older books, too</label><p>Your request is sent to our hosted AI service only when you submit. Filters apply to that request. Your private shelf and passport notes stay private.</p></div>}
          </form>
        {searchMode === 'ai' && <section className="panel ai-results-panel" ref={aiResultsRef} tabIndex={-1} aria-labelledby="ai-results-title" aria-busy={aiLoading}>
          <div className="discover-heading"><div><span className="eyebrow">PIP’S VERY PARTICULAR PICKS</span><h2 id="ai-results-title">Your next plot twist<span className="purple"> ✦</span></h2><p className="results-subtitle">Real catalog books, matched to your description. A suggestion, never a reading assignment.</p></div><span className="ai-results-stamp"><Icon name="spark" size={17} />{aiResult ? 'Picked with AI' : 'AI recommendations'}</span></div>
          {aiSubmitted && (aiResult || aiLoading) && <p className="ai-submitted-request"><strong>Reading request:</strong> “{aiSubmitted.prompt}”<small>{aiSubmitted.genre || 'Any genre'} · {aiSubmitted.minRating ? `${aiSubmitted.minRating}+ stars` : 'Any rating'} · {aiSubmitted.includeOlder ? 'Older books included' : 'Last five years'}</small></p>}
          {!aiLoading && !aiResult && !aiError && !aiFeedback && <p className="ai-empty-hint">Describe what you would enjoy, then press Ask Pip. For example: “I need a fantasy book with dragons.”</p>}
          {aiLoading && <div className="results-loading" role="status"><span className="spinner" /><div><strong>Interpreting your mood. Checking real books.</strong><p>This can take a little while. Pip is a goblin, not a speed-reader.</p></div><button className="text-button" onClick={cancelRecommendations}>Cancel</button></div>}
          {aiError && <p className="inline-error" role="alert">{aiError} Choose Title or author above for regular catalog search.</p>}
          {aiFeedback && <p className="ai-result-message" role="status">{aiFeedback}</p>}
          {aiResult && <><p className="ai-result-message" role="status">{aiResult.message || `${aiResult.books.length} ${aiResult.books.length === 1 ? 'book' : 'books'} for your next chapter.`}</p><div className="ai-result-sources"><span>{aiResult.minPublicationYear ? `First published ${aiResult.minPublicationYear}–${aiResult.maxPublicationYear}` : 'Older books included'} · English editions</span>{aiResult.sources?.length > 0 && <span>Book data: {aiResult.sources.join(' · ')}</span>}{aiResult.cached && <span>Reusing a recent match to save the shared allowance.</span>}</div><div className="book-cards">{aiResult.books.map((book) => <BookCard key={book.id} book={book} entry={shelf.find((entry) => entry.workId === book.id)} busy={pendingKey === `add:${book.id}`} locked={!!pendingKey || sessionLoading} onOpen={setSelectedBook} onAdd={addBook} onStatus={updateStatus} />)}</div>{!aiResult.books.length && <p className="ai-empty-hint">Try fewer requirements, a broader genre, or the “Include older books” option. We won’t invent a match.</p>}<p className="catalog-note">AI explanations are based on available catalog information and can be imperfect. Ratings belong to the source named in each book’s details. Google Books edition dates may differ from a work’s original publication year.</p></>}
        </section>}

          {searchMode === 'catalog' && <>
          {pageMeta.source && <div className={`catalog-source ${pageMeta.source === 'curated' ? 'is-offline' : ''}`} role="status"><strong>{pageMeta.source === 'curated' ? 'Offline collection — live connection unavailable' : 'Live Open Library catalog'}</strong>{pageMeta.message && <span>{pageMeta.message}</span>}{pageMeta.source === 'curated' && <button className="text-button" disabled={!!loadingKind} onClick={() => loadResults('featured', { filters: successfulQuery.current || { title: title.trim(), genre, minRating } })}>Retry Open Library <Icon name="arrow" size={14} /></button>}</div>}
          <div className="results-toolbar"><span className="results-label"><Icon name="leaf" size={14} />{pageMeta.minPublicationYear ? `English editions · First published ${pageMeta.minPublicationYear}–${pageMeta.maxPublicationYear}` : 'Matching titles from every era'}</span><span>{!loadingKind && loadedCountLabel}</span></div>
          {loadingKind && loadingKind !== 'more' && <div className="results-loading" role="status"><span className="spinner" /><div><strong>Looking through the stacks…</strong><p>Fetching real books and reader ratings.</p></div><button className="text-button" onClick={cancelResults}>Cancel</button></div>}
          {resultError && <div className="inline-error results-error" role="alert"><Icon name="close" size={17} /><span>{resultError}</span><button className="text-button" onClick={() => loadResults('featured')}>Try browsing picks</button></div>}
          <div className={`book-cards ${loadingKind && loadingKind !== 'more' ? 'results-dimmed' : ''}`} aria-busy={!!loadingKind}>{books.map((book) => <BookCard key={book.id} book={book} entry={shelf.find((entry) => entry.workId === book.id)} busy={pendingKey === `add:${book.id}`} locked={!!pendingKey || sessionLoading} onOpen={setSelectedBook} onAdd={addBook} onStatus={updateStatus} />)}</div>
          {!loadingKind && !books.length && !resultError && <div className="no-results"><Icon name="book" size={38} /><h3>{pageMeta.hasMore ? "No matches on this page." : "A little too specific for these shelves."}</h3><p>{pageMeta.hasMore ? "Load more to keep looking for matching books." : pageMeta.minPublicationYear ? `No recent matches in ${pageMeta.minPublicationYear}–${pageMeta.maxPublicationYear}. Try another genre or rating, or search a title or author to find older books.` : "Try a shorter title, a broader genre, or a lower rating. Books without a reader rating are excluded when a minimum is selected."}</p><button className="button" onClick={() => { setTitle(''); setGenre(''); setMinRating(''); loadResults('featured', { filters: { genre: '', minRating: '' } }) }}>Clear the filters</button></div>}
          <div className="catalog-pagination" aria-live="polite">
            <p>{loadedCountLabel}</p>
            {!loadingKind && books.length > 0 && pageMeta.hasMore && pageMeta.books?.length === 0 && <p>No matches on this page; load more or adjust your filters.</p>}
            {pageMeta.hasMore && <button className="button button-purple" disabled={!!loadingKind} onClick={() => loadResults('more', { append: true })}>{loadingKind === 'more' ? <><span className="spinner" />Loading more books...</> : <>Load 11 more books<Icon name="arrow" size={16} /></>}</button>}
            {loadingKind === 'more' && <button className="text-button" onClick={cancelResults}>Cancel</button>}
          </div>
          <p className="catalog-note">Children’s books have their own home: choose Kids in Genre. Surprise Me skips them. Audience labels depend on Open Library’s cataloging.<br />Books, covers & reader ratings from Open Library. Missing cover? We dressed it up. Ratings are never made up.</p>
          </>}
        </section>
      </main>
      <footer className="site-footer"><span className="footer-brand"><Icon name="book" size={17} />bookbuddy<span>✦</span></span><p>Made for curious minds & gloriously overflowing shelves.</p><span className="footer-local"><span />Your little reading nook</span></footer>
    </div>
    {passportEntry && user && <PassportModal key={passportEntry.id} entry={passportEntry} readerName={user.name} busy={!!pendingKey} onClose={() => setPassportId(null)} onSave={savePassport} onViewBook={(entry) => { setPassportId(null); setSelectedBook(entry) }} />}
    {libraryOpen && user && <LibraryModal entries={shelf} busy={!!pendingKey} onClose={() => setLibraryOpen(false)} onOpen={setSelectedBook} onStatus={updateStatus} />}
    {authMode && <AuthModal initialMode={authMode} onClose={() => { setAuthMode(null); pendingAction.current = null }} onSuccess={authenticated} />}
    {selectedBook && !authMode && <BookModal key={selectedBook.workId || selectedBook.id} user={user} book={selectedEntry || selectedBook} entry={selectedEntry} busy={!!pendingKey} onClose={() => setSelectedBook(null)} onAdd={addBook} onStatus={updateStatus} onRemove={removeBook} onReviewAuth={() => openAuth('login', { type: 'review', book: selectedBook })} onNotify={notify} onOpenPassport={openPassport} />}
    {toast && <div className="toast" role="status"><span className="toast-spark" aria-hidden="true">✦</span><span>{toast}</span><button aria-label="Dismiss notification" onClick={() => setToast('')}><Icon name="close" size={16} /></button></div>}
  </>
}

export default App
