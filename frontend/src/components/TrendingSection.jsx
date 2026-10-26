import { useEffect, useRef, useState } from 'react'
import { api, catalogQuery } from '../api'
import { GENRES } from '../bookData'
import BookCard from './BookCard'
import Icon from './Icon'
import './TrendingSection.css'

const emptyFeed = { books: [], page: 0, nextCursor: null, hasMore: false, loading: 'initial', error: '', message: '' }

export default function TrendingSection({ sectionRef, shelf, pendingKey, locked, onOpen, onAdd, onStatus }) {
  const [enabled, setEnabled] = useState(false)
  const [request, setRequest] = useState({ genre: '', minRating: '', page: 1, cursor: null, revision: 0 })
  const [feed, setFeed] = useState(emptyFeed)
  const [scroll, setScroll] = useState({ back: false, next: false })
  const viewportRef = useRef(null)
  const trackRef = useRef(null)

  // Fetch when this shelf approaches the screen. No polling or background timer.
  useEffect(() => {
    if (!window.IntersectionObserver || !sectionRef.current) return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setEnabled(true)
        observer.disconnect()
      }
    }, { rootMargin: '250px' })
    observer.observe(sectionRef.current)
    return () => observer.disconnect()
  }, [sectionRef])

  // Each genre/continuation gets its own abortable request. Late responses from
  // a previous choice cannot overwrite the reader's new choice.
  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    let alive = true
    async function load() {
      try {
        const query = new URLSearchParams(catalogQuery(request).slice(1))
        query.set('page', request.page)
        if (request.cursor) query.set('cursor', request.cursor)
        const response = await api(`/api/books/trending?${query}`, { signal: controller.signal, timeout: 35000 })
        if (response.source !== 'openlibrary') throw new Error(response.message || 'Current trends are unavailable. Try again shortly.')
        if (!alive) return
        setFeed((previous) => ({ ...response, loading: '', error: '', books: request.page > 1
          ? [...new Map([...previous.books, ...response.books].map((book) => [book.id, book])).values()]
          : response.books }))
      } catch (error) {
        if (alive && !controller.signal.aborted) setFeed((previous) => ({ ...previous, loading: '', error: error.message }))
      }
    }
    load()
    return () => { alive = false; controller.abort() }
  }, [enabled, request])

  useEffect(() => {
    if (!window.ResizeObserver) return
    const observer = new ResizeObserver(() => {
      const element = viewportRef.current
      if (element) setScroll({ back: element.scrollLeft > 2, next: element.scrollLeft + element.clientWidth < element.scrollWidth - 2 })
    })
    if (viewportRef.current) observer.observe(viewportRef.current)
    if (trackRef.current) observer.observe(trackRef.current)
    return () => observer.disconnect()
  }, [])

  function changeFilter(name, value) {
    setFeed(emptyFeed)
    setRequest((previous) => ({ ...previous, [name]: value, page: 1, cursor: null, revision: previous.revision + 1 }))
    setEnabled(true)
  }

  function refresh() {
    setFeed((previous) => ({ ...previous, loading: 'initial', error: '' }))
    setRequest((previous) => ({ ...previous, page: 1, cursor: null, revision: previous.revision + 1 }))
    setEnabled(true)
  }

  function more() {
    if (feed.loading || !feed.hasMore) return
    setFeed((previous) => ({ ...previous, loading: 'more', error: '' }))
    setRequest((previous) => ({ ...previous, page: feed.page + 1, cursor: feed.nextCursor, revision: previous.revision + 1 }))
  }

  function move(direction) {
    const element = viewportRef.current
    element?.scrollBy({ left: direction * element.clientWidth * .9, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' })
  }

  return <section className="panel trending-panel" id="trending" ref={sectionRef} aria-labelledby="trending-title">
    <div className="trending-heading">
      <div><span className="eyebrow"><span className="trending-dot" />THE READING ROOM IS BUZZING</span><h2 id="trending-title">Trending <em>now.</em><span aria-hidden="true"> ↗</span></h2><p>English editions making the rounds. First published in the last five years.</p></div>
      <span className="trending-sticker" aria-hidden="true"><Icon name="spark" size={23} />CURRENT<br />OBSESSIONS</span>
    </div>
    <div className="trending-tools">
      <div className="filter-fields"><label htmlFor="trending-genre">Genre<select id="trending-genre" value={request.genre} onChange={(event) => changeFilter('genre', event.target.value)}><option value="">All genres</option>{GENRES.map((genre) => <option key={genre}>{genre}</option>)}</select></label><label htmlFor="trending-rating">Reader rating<select id="trending-rating" value={request.minRating} onChange={(event) => changeFilter('minRating', event.target.value)}><option value="">Any rating</option><option value="3.5">3.5+ stars</option><option value="4">4+ stars</option><option value="4.5">4.5+ stars</option></select></label></div>
      <div className="trending-actions"><button className="text-button" onClick={refresh} disabled={!!feed.loading && enabled}><Icon name="refresh" size={14} />Refresh picks</button><div className="trending-arrows" aria-label="Scroll trending books"><button className="button" aria-label="Previous trending books" aria-controls="trending-books" onClick={() => move(-1)} disabled={!scroll.back}><span className="reverse-arrow"><Icon name="arrow" size={17} /></span></button><button className="button" aria-label="Next trending books" aria-controls="trending-books" onClick={() => move(1)} disabled={!scroll.next}><Icon name="arrow" size={17} /></button></div></div>
    </div>
    <div className="trending-status" aria-live="polite">{enabled && feed.loading ? <><span className="spinner" />{feed.loading === 'more' ? 'Finding more of the buzz…' : 'Checking the current reading buzz…'}</> : feed.books.length > 0 ? <><span className="trending-live">OPEN LIBRARY TRENDS</span><span>{feed.books.length} picks{request.genre ? ` · ${request.genre}` : ' · Across genres'}{feed.minPublicationYear ? ` · ${feed.minPublicationYear}–${feed.maxPublicationYear}` : ''}</span></> : null}</div>
    {feed.error && <div className="inline-error" role="alert"><span>{feed.error}{feed.books.length > 0 ? ' Your earlier picks are still shown.' : ''}</span><button className="text-button" onClick={refresh}>Try again</button></div>}
    {!enabled && <div className="trending-placeholder"><p>A little buzz from beyond your bookshelf.</p><button className="button button-purple" onClick={refresh}>Show trending picks <Icon name="arrow" size={16} /></button></div>}
    <div className={`trending-viewport ${feed.loading === 'initial' ? 'results-dimmed' : ''}`} id="trending-books" ref={viewportRef} aria-label="Trending book carousel" role="region" tabIndex={feed.books.length ? 0 : -1} aria-busy={enabled && !!feed.loading} onScroll={(event) => {
      const element = event.currentTarget
      setScroll({ back: element.scrollLeft > 2, next: element.scrollLeft + element.clientWidth < element.scrollWidth - 2 })
    }}><div className="trending-track" ref={trackRef}>{feed.books.map((book) => <BookCard key={book.id} book={book} entry={shelf.find((entry) => entry.workId === book.id)} busy={pendingKey === `add:${book.id}`} locked={locked} onOpen={onOpen} onAdd={onAdd} onStatus={onStatus} />)}</div></div>
    {enabled && !feed.loading && !feed.error && feed.books.length === 0 && <div className="trending-empty"><Icon name="book" size={32} /><h3>{feed.hasMore ? 'The next page may have your next favorite.' : 'A quieter corner of the reading room.'}</h3><p>{feed.hasMore ? 'Load more to keep looking for matches.' : 'No current trend picks match these filters. Try another genre or rating.'}</p></div>}
    {feed.hasMore && <div className="trending-more"><button className="button button-purple" onClick={more} disabled={!!feed.loading}>{feed.loading === 'more' ? 'Finding more…' : 'Load 11 more trending books'}<Icon name="arrow" size={16} /></button></div>}
    {feed.books.length > 0 && feed.hasMore && feed.message && <p className="trending-continuation-note">{feed.message}</p>}
    <p className="catalog-note">English editions of recent publications trending on Open Library. Search a title or author in Discover to find older books. Children’s books have their own space—choose Kids. Catalog labels can be imperfect.</p>
  </section>
}
