// The browser talks only to our backend. Vite proxies /api in development;
// the packaged Spring Boot app serves these same paths on localhost:8080.
export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export async function api(path, { method = 'GET', body, signal, timeout = 25000 } = {}) {
  const controller = new AbortController()
  const abort = () => controller.abort(signal?.reason)
  if (signal?.aborted) abort()
  else signal?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(() => controller.abort(new DOMException('The request took too long. Please try again.', 'TimeoutError')), timeout)

  try {
    const headers = { Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    // Fetch a fresh token before mutations, including login/logout. Spring
    // rotates the token when authentication changes.
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      const tokenResponse = await fetch('/api/auth/csrf', { credentials: 'same-origin', signal: controller.signal })
      if (!tokenResponse.ok) throw new ApiError('Could not establish a secure session. Refresh and try again.', tokenResponse.status)
      const csrf = await tokenResponse.json()
      headers[csrf.headerName] = csrf.token
    }
    const response = await fetch(path, {
      method, headers, credentials: 'same-origin', signal: controller.signal,
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    })
    if (response.status === 204) return null
    const data = await response.json().catch(() => null)
    if (!response.ok) {
      const message = data?.message || data?.detail || (response.status === 401
        ? 'Please sign in to use your personal shelf.'
        : response.status === 403 ? 'Your session changed. Refresh the page and try again.'
          : `Something went wrong (${response.status}). Please try again.`)
      throw new ApiError(message, response.status)
    }
    return data
  } catch (error) {
    if (controller.signal.aborted && !signal?.aborted) throw new Error('That took too long. Please try again in a moment.', { cause: error })
    if (error instanceof TypeError) throw new Error('Book Buddy could not reach the server. Check that Spring Boot is running on port 8080.', { cause: error })
    throw error
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

export function catalogQuery({ title = '', genre = '', minRating = '' } = {}) {
  const query = new URLSearchParams()
  if (title.trim()) query.set('title', title.trim())
  if (genre) query.set('genre', genre)
  if (minRating) query.set('minRating', minRating)
  return query.size ? `?${query}` : ''
}
