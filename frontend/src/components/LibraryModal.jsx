import { useState } from 'react'
import Modal from './Modal'
import Icon from './Icon'
import BookCard from './BookCard'
import { STATUSES } from '../bookData'

const PAGE_SIZE = 24

export default function LibraryModal({ entries, busy, onClose, onOpen, onStatus }) {
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [genre, setGenre] = useState('')
  const [year, setYear] = useState('')
  const [sort, setSort] = useState('title')
  const [layout, setLayout] = useState('grid')
  const [page, setPage] = useState(0)
  const genres = [...new Set(entries.map((entry) => entry.genre).filter(Boolean))].sort()
  const years = [...new Set(entries.filter((entry) => entry.status === 'READ' && entry.finishDate).map((entry) => entry.finishDate.slice(0, 4)))].sort().reverse()
  const matches = entries.filter((entry) => {
    const haystack = `${entry.title} ${entry.author}`.toLowerCase()
    return haystack.includes(query.trim().toLowerCase()) && (!status || entry.status === status) && (!genre || entry.genre === genre) && (!year || (year === 'unknown' ? entry.status === 'READ' && !entry.finishDate : entry.status === 'READ' && entry.finishDate?.startsWith(year)))
  }).sort((a, b) => sort === 'finished' ? (b.finishDate || '').localeCompare(a.finishDate || '') || a.title.localeCompare(b.title) : a.title.localeCompare(b.title))
  const maxPage = Math.max(0, Math.ceil(matches.length / PAGE_SIZE) - 1)
  const currentPage = Math.min(page, maxPage)
  const visible = matches.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)
  function change(setter, value) { setter(value); setPage(0) }

  return (
    <Modal titleId="library-title" onClose={onClose} className="library-modal">
      <span className="eyebrow">ALL YOUR WORLDS IN ONE PLACE</span>
      <h2 id="library-title">My entire little library<span className="purple">.</span></h2>
      <p className="modal-intro">{entries.length} saved {entries.length === 1 ? 'book' : 'books'}. Find the story you’re looking for.</p>
      <div className="library-filters">
        <label className="library-search">Search your books<input type="search" placeholder="A title or author…" value={query} onChange={(e) => change(setQuery, e.target.value)} /></label>
        <label>Reading status<select value={status} onChange={(e) => change(setStatus, e.target.value)}><option value="">All statuses</option>{STATUSES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>
        <label>Genre<select value={genre} onChange={(e) => change(setGenre, e.target.value)}><option value="">All genres</option>{genres.map((item) => <option key={item}>{item}</option>)}</select></label>
        <label>Finished in<select value={year} onChange={(e) => change(setYear, e.target.value)}><option value="">Any year</option>{years.map((item) => <option key={item}>{item}</option>)}<option value="unknown">Date not recorded</option></select></label>
        <label>Sort by<select value={sort} onChange={(e) => change(setSort, e.target.value)}><option value="title">Title A–Z</option><option value="finished">Finish date, newest first</option></select></label>
      </div>
      <div className="library-toolbar"><span aria-live="polite">{matches.length ? `${currentPage * PAGE_SIZE + 1}–${Math.min((currentPage + 1) * PAGE_SIZE, matches.length)} of ${matches.length} books` : 'No matching books'}</span><div className="layout-toggle" aria-label="Library layout"><button aria-pressed={layout === 'grid'} onClick={() => setLayout('grid')}>Grid</button><button aria-pressed={layout === 'list'} onClick={() => setLayout('list')}>List</button></div></div>
      {layout === 'grid' ? <div className="book-cards library-cards">{visible.map((book) => <BookCard key={book.id} book={book} entry={book} locked={busy} onOpen={onOpen} onStatus={onStatus} />)}</div> : <div className="library-list">{visible.map((book) => <article key={book.id}><div><button className="text-button book-title" onClick={() => onOpen(book)}>{book.title}</button><p>{book.author}</p><small>{book.genre || 'Genre unavailable'}{book.status === 'READ' ? ` · Finished: ${book.finishDate || 'date not recorded'}` : ''}</small></div><label className="sr-only" htmlFor={`library-status-${book.id}`}>Status for {book.title}</label><select id={`library-status-${book.id}`} value={book.status} disabled={busy} onChange={(e) => onStatus(book.id, e.target.value)}>{STATUSES.map((item) => <option value={item.value} key={item.value}>{item.label}</option>)}</select><button className="small-link" onClick={() => onOpen(book)}>Details <Icon name="arrow" size={14} /></button></article>)}</div>}
      {!visible.length && <div className="no-results"><Icon name="book" size={36} /><h3>{entries.length ? 'No books match those filters.' : 'Your library is waiting for chapter one.'}</h3><p>{entries.length ? 'Try another title, status, genre, or finish year.' : 'Browse the catalog and save a book to get started.'}</p></div>}
      <div className="library-page-controls"><button className="button" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>← Previous</button><span>Page {currentPage + 1} of {maxPage + 1}</span><button className="button" disabled={currentPage >= maxPage} onClick={() => setPage(currentPage + 1)}>Next →</button></div>
    </Modal>
  )
}
