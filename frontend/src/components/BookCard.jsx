import { useState } from 'react'
import Icon from './Icon'

import { bookStyle, STATUSES } from '../bookData'

export function BookCover({ book, className = '' }) {
  const [failedId, setFailedId] = useState(null)
  const id = book.workId || book.id
  const googleVolume = /^googlebooks:([A-Za-z0-9_-]{1,100})$/.exec(id || '')?.[1]
  const cover = googleVolume ? `https://books.google.com/books/content?id=${encodeURIComponent(googleVolume)}&printsec=frontcover&img=1&zoom=1&source=gbs_api` : book.coverId ? `https://covers.openlibrary.org/b/id/${book.coverId}-L.jpg?default=false` : ''
  const showImage = cover && failedId !== cover
  return <div className={`book-cover ${className}`} style={bookStyle(book)}>
    <div className="illustrated-cover" aria-hidden="true">
      <span className="cover-topline">A LITTLE DOOR TO SOMEWHERE ELSE</span>
      <strong>{book.title}</strong>
      <span className="cover-ornament"><Icon name="spark" size={42} /></span>
      <span className="cover-author">{book.author}</span>
    </div>
    {showImage && <img src={cover} alt={`Cover of ${book.title}`} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailedId(cover)} />}
  </div>
}

export function Rating({ book }) {
  const rating = Number(book.rating)
  const source = (book.workId || book.id || '').startsWith('googlebooks:') || /google/i.test(book.source || '') ? 'Google Books' : 'Open Library'
  return <span className="rating" title={rating > 0 ? `${book.ratingCount || 'Available'} ${source} ratings` : `No ${source} rating available`} aria-label={rating > 0 ? `${rating.toFixed(1)} out of 5, ${source}` : `Not rated on ${source}`}>
    <Icon name="star" size={13} /> {rating > 0 ? rating.toFixed(1) : 'Not rated'}
    {rating > 0 && <span className="rating-scale">/ 5</span>}
  </span>
}

export default function BookCard({ book, entry, busy, locked, onOpen, onAdd, onStatus }) {
  return <article className="book-card">
    <button className="cover-button" onClick={() => onOpen(book)} aria-label={`View ${book.title} by ${book.author}`}><BookCover book={book} /></button>
    <div className="card-body">
      <div className="card-meta"><span>{book.genre || 'Fiction'}</span><Rating book={book} /></div>
      <h3><button className="text-button book-title" onClick={() => onOpen(book)}>{book.title}</button></h3>
      <p className="book-author">{book.author || 'Unknown author'}</p>
      <p className="book-year">{book.year ? `${(book.workId || book.id || '').startsWith('googlebooks:') ? 'Edition published' : 'First published'} ${book.year}` : 'Publication year unavailable'}</p>
      {book.reason && <p className="book-reason"><Icon name="spark" size={13} />{book.reason}</p>}
    </div>
    {entry ? <label className="saved-card-status"><span><Icon name="check" size={12} />On your shelf</span><select aria-label={`Reading status for ${book.title}`} value={entry.status} disabled={busy || locked} onChange={(event) => onStatus(entry.id, event.target.value)}>{STATUSES.map((status) => <option key={status.value} value={status.value}>{status.label}</option>)}</select>{entry.status === 'READ' && <small>Finished: {entry.finishDate || 'date not recorded'}</small>}</label> : <div className="card-save-actions">
      <button className="button card-add" disabled={busy || locked} onClick={() => onAdd(book, 'WANT_TO_READ')}><Icon name="plus" size={14} />{busy ? 'Saving…' : 'Want to Read'}</button>
      <button className="button card-already-read" disabled={busy || locked} onClick={() => onAdd(book, 'READ')}><Icon name="check" size={14} />Already Read</button>
    </div>}
  </article>
}
