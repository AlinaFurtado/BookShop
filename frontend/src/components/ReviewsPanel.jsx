import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import Icon from './Icon'

function reviewPath(workId, page = 1) {
  return `/api/reviews?${new URLSearchParams({ workId, page })}`
}

function ReviewStars({ rating }) {
  return <span className="review-stars" aria-label={`${rating} out of 5 stars`}>{[1, 2, 3, 4, 5].map((star) => <Icon key={star} name="star" size={13} className={star <= rating ? 'is-filled' : ''} />)}</span>
}

export default function ReviewsPanel({ workId, user, onAuth, onNotify }) {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [rating, setRating] = useState(0)
  const [text, setText] = useState('')
  const [spoiler, setSpoiler] = useState(false)
  const request = useRef({ sequence: 0, controller: null })
  const editor = useRef(null)
  const own = data?.currentUserReview

  useEffect(() => {
    const active = request.current
    const controller = new AbortController()
    active.controller?.abort()
    active.controller = controller
    const sequence = ++active.sequence
    api(reviewPath(workId), { signal: controller.signal }).then((response) => {
      if (active.sequence === sequence) setData(response)
    }).catch((err) => {
      if (!controller.signal.aborted && active.sequence === sequence) setError(err.message)
    }).finally(() => {
      if (active.sequence === sequence) setLoading(false)
    })
    return () => active.controller?.abort()
  }, [workId, user?.id])

  async function loadReviews(page = 1, append = false) {
    request.current.controller?.abort()
    const controller = new AbortController()
    request.current.controller = controller
    const sequence = ++request.current.sequence
    setLoading(true)
    setError('')
    try {
      const response = await api(reviewPath(workId, page), { signal: controller.signal })
      if (sequence !== request.current.sequence) return
      setData((current) => append ? { ...response, reviews: [...new Map([...(current?.reviews || []), ...response.reviews].map((review) => [review.id, review])).values()] } : response)
    } catch (err) {
      if (!controller.signal.aborted && sequence === request.current.sequence) setError(err.message)
    } finally {
      if (sequence === request.current.sequence) setLoading(false)
    }
  }

  function editReview(review) {
    setRating(review.rating)
    setText(review.text)
    setSpoiler(review.spoiler)
    setEditing(true)
    setConfirmDelete(false)
    requestAnimationFrame(() => editor.current?.focus())
  }

  async function saveReview(event) {
    event.preventDefault()
    if (busy) return
    if (rating < 1 || rating > 5 || !text.trim()) { setError('Choose a star rating and tell us what you thought.'); return }
    setBusy(true)
    setError('')
    try {
      await api(own ? `/api/reviews/${own.id}` : '/api/reviews', { method: own ? 'PATCH' : 'POST', body: { workId, rating, text: text.trim(), spoiler } })
      setEditing(false)
      await loadReviews()
      onNotify(own ? 'Your review has been updated.' : 'Your review is part of the Book Buddy conversation.')
    } catch (err) { setError(err.message) }
    finally { setBusy(false) }
  }

  async function deleteReview() {
    if (busy || !own) return
    setBusy(true)
    setError('')
    try {
      await api(`/api/reviews/${own.id}`, { method: 'DELETE' })
      setConfirmDelete(false); setEditing(false); setRating(0); setText(''); setSpoiler(false)
      await loadReviews()
      onNotify('Your review has been removed.')
    } catch (err) { setError(err.message) }
    finally { setBusy(false) }
  }

  return (
    <section className="reviews-panel" aria-labelledby="reviews-title">
      <div className="review-heading"><div><span className="eyebrow">REAL READERS. REAL OPINIONS.</span><h3 id="reviews-title">Book Buddy reviews<span className="purple"> ✦</span></h3></div><div className="community-average">{data?.count ? <><Icon name="star" size={19} /><strong>{Number(data.average).toFixed(1)}</strong><span>/ 5 · {data.count} {data.count === 1 ? 'review' : 'reviews'}</span></> : <span>{loading ? 'Loading reviews…' : 'No community ratings yet'}</span>}</div></div>
      <p className="reviews-source">Written here by Book Buddy readers. Separate from the Open Library rating above.</p>
      {error && <p className="inline-error" role="alert">{error}<button className="text-button" onClick={() => loadReviews()}>Reload reviews</button></p>}
      {!user && <div className="review-signin"><p>Your honest opinion belongs here.</p><button className="button button-lime" onClick={onAuth}>Sign in to write a review <Icon name="arrow" size={14} /></button></div>}
      {user && own && !editing && <div className="own-review-summary"><span><Icon name="check" size={14} />You’ve reviewed this book.</span><div><button className="text-button" disabled={busy} onClick={() => editReview(own)}>Edit my review</button><button className="text-button remove-link" disabled={busy} onClick={() => setConfirmDelete(true)}>Delete</button></div></div>}
      {confirmDelete && <div className="remove-confirm"><span>Permanently delete your review?</span><div><button className="text-button" disabled={busy} onClick={() => setConfirmDelete(false)}>Keep it</button><button className="button button-coral small-button" disabled={busy} onClick={deleteReview}>Yes, delete</button></div></div>}
      {user && data && (!own || editing) && <form className="review-form" onSubmit={saveReview}>
        <fieldset disabled={busy}><legend>{own ? 'Edit your rating' : 'Your rating'}</legend><div className="star-picker">{[1, 2, 3, 4, 5].map((star) => <label key={star} className={star <= rating ? 'is-selected' : ''}><input type="radio" name="review-rating" value={star} checked={rating === star} onChange={() => setRating(star)} required /><Icon name="star" size={25} /><span className="sr-only">{star} {star === 1 ? 'star' : 'stars'}</span></label>)}</div></fieldset>
        <label htmlFor="review-text">{own ? 'Your review' : 'What did you think?'}<textarea ref={editor} id="review-text" value={text} onChange={(e) => setText(e.target.value)} required minLength={1} maxLength={2000} rows={4} disabled={busy} placeholder="The characters, the writing, the bit that stayed with you…" /></label>
        <div className="review-form-bottom"><label className="spoiler-check"><input type="checkbox" checked={spoiler} onChange={(e) => setSpoiler(e.target.checked)} disabled={busy} />Contains spoilers</label><span>{text.length}/2000</span></div>
        <div className="review-submit"><button className="button button-purple" disabled={busy || loading} type="submit">{busy ? 'Saving…' : own ? 'Save my review' : 'Publish my review'}</button>{own && <button className="text-button" disabled={busy} type="button" onClick={() => setEditing(false)}>Cancel editing</button>}</div>
      </form>}
      <div className="review-list" aria-busy={loading}>
        {data?.reviews.map((review) => <article className="reader-review" key={review.id}>
          <div className="reader-review-top"><span className="review-avatar" aria-hidden="true">{review.userName?.slice(0, 1).toUpperCase() || 'R'}</span><div><strong>{review.userName || 'Book Buddy reader'}{review.userId === user?.id ? ' · You' : ''}</strong><span>{new Date(review.createdAt).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })}{new Date(review.updatedAt).getTime() - new Date(review.createdAt).getTime() > 1000 ? ' · Edited' : ''}</span></div><ReviewStars rating={review.rating} /></div>
          {review.spoiler ? <details className="spoiler-review"><summary>Spoiler ahead · reveal review</summary><p>{review.text}</p></details> : <p className="review-text">{review.text}</p>}
        </article>)}
        {!loading && data?.count === 0 && <p className="no-reviews">No reviews yet. The first word could be yours.</p>}
      </div>
      {loading && <p className="review-loading" role="status"><span className="spinner" />Loading reader reviews…</p>}
      {data?.hasMore && <button className="button review-load-more" disabled={loading || busy} onClick={() => loadReviews(data.page + 1, true)}>Load more reviews <Icon name="arrow" size={14} /></button>}
    </section>
  )
}
