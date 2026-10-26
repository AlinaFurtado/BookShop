import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import Icon from './Icon'

function sourcePath(workId) {
  return `/api/reviews/external?${new URLSearchParams({ workId })}`
}

export default function ExternalReviewsPanel({ book }) {
  const workId = book.workId || book.id
  const supported = /^\/works\/OL\d{1,18}W$/.test(workId || '')
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(supported)
  const [error, setError] = useState('')
  const request = useRef({ sequence: 0, controller: null })
  const search = `${book.title} ${book.author === 'Unknown author' ? '' : book.author || ''}`.trim()
  const goodreadsUrl = `https://www.goodreads.com/search?${new URLSearchParams({ q: search, search_type: 'books' })}`
  const googleUrl = `https://books.google.com/books?${new URLSearchParams({ q: search })}`

  useEffect(() => {
    if (!supported) return
    const active = request.current
    const controller = new AbortController()
    active.controller?.abort()
    active.controller = controller
    const sequence = ++active.sequence
    api(sourcePath(workId), { signal: controller.signal, timeout: 30000 }).then((response) => {
      if (active.sequence === sequence) setData(response)
    }).catch((err) => {
      if (!controller.signal.aborted && active.sequence === sequence) setError(err.message)
    }).finally(() => { if (active.sequence === sequence) setLoading(false) })
    return () => controller.abort()
  }, [workId, supported])

  async function reload() {
    request.current.controller?.abort()
    const controller = new AbortController()
    request.current.controller = controller
    const sequence = ++request.current.sequence
    setLoading(true)
    setError('')
    try {
      const response = await api(sourcePath(workId), { signal: controller.signal, timeout: 30000 })
      if (request.current.sequence === sequence) setData(response)
    } catch (err) {
      if (!controller.signal.aborted && request.current.sequence === sequence) setError(err.message)
    } finally { if (request.current.sequence === sequence) setLoading(false) }
  }

  return <section className="external-reviews-panel" aria-labelledby="external-reviews-title">
    <div className="review-heading"><div><span className="eyebrow">OPINIONS FROM BEYOND OUR SHELF</span><h3 id="external-reviews-title">What other readers say<span className="purple"> ✦</span></h3></div><span className="external-review-source"><Icon name="book" size={14} />Internet Archive</span></div>
    <p className="reviews-source">Existing reader reviews from archived editions of this book. These may discuss the scan or edition as well as the story. Ratings stay separate from Book Buddy and Open Library.</p>
    {loading && <p className="review-loading" role="status"><span className="spinner" />Looking for existing reader reviews…</p>}
    {error && <p className="inline-error" role="alert">{error} You can still read or write Book Buddy reviews below.</p>}
    {!loading && !error && <p className="external-review-message" role="status">{supported ? data?.message : 'This book has no linked catalog identifier for an external review lookup.'}</p>}
    <div className="review-list" aria-busy={loading}>
      {data?.reviews.map((review) => <article className="reader-review external-reader-review" key={review.id}>
        <div className="reader-review-top"><span className="review-avatar" aria-hidden="true">{review.reviewer.slice(0, 1).toUpperCase()}</span><div><strong>{review.reviewer}</strong><span>Internet Archive{review.date ? ` · ${new Date(review.date).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })}` : ''}</span></div>{review.rating ? <span className="review-stars" aria-label={`${review.rating} out of 5 stars`}>{[1, 2, 3, 4, 5].map((star) => <Icon name="star" key={star} size={13} className={star <= review.rating ? 'is-filled' : ''} />)}</span> : <span className="external-review-unrated">No star rating supplied</span>}</div>
        <p className="external-review-edition">Edition: {review.editionTitle}</p>
        <details className="spoiler-review external-review-excerpt"><summary>Read review excerpt · may contain spoilers</summary>{review.title && <h4>{review.title}</h4>}{review.excerpt && <p>{review.excerpt}</p>}</details>
        <a className="external-review-link" href={review.sourceUrl} target="_blank" rel="noopener noreferrer nofollow">{review.shortened ? 'Read the full review on Internet Archive' : 'View original reviews on Internet Archive'} ↗</a>
      </article>)}
    </div>
    {supported && (error || data?.status === 'unavailable' || data?.status === 'partial') && <button className="button review-load-more" disabled={loading} onClick={reload}>Retry external reviews <Icon name="arrow" size={14} /></button>}
    {data?.editions?.length > 0 && <details className="external-review-editions"><summary>Archived editions checked ({data.editions.length})</summary><ul>{data.editions.map((edition) => <li key={edition.id}><a href={edition.url} target="_blank" rel="noopener noreferrer nofollow">{edition.title} ↗</a></li>)}</ul></details>}
    <div className="other-reader-links"><p>Explore more opinions on other book sites:</p><div><a href={goodreadsUrl} target="_blank" rel="noopener noreferrer">Search Goodreads ↗</a><a href={googleUrl} target="_blank" rel="noopener noreferrer">Find on Google Books ↗</a></div><small>These open a book search on the source site. Their written reviews are not imported here.</small></div>
  </section>
}
