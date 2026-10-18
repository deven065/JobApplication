export const API = import.meta.env.VITE_API_BASE_URL || '/api'

/** JSON request to the backend; failures become Errors carrying the server's own explanation. */
export async function request(path, options) {
  let response
  try { response = await fetch(`${API}${path}`, options) }
  catch { throw new Error('Cannot reach the backend. Start Spring Boot on port 8081 and try again.') }
  const text = await response.text()
  let data
  try { data = text ? JSON.parse(text) : null } catch { data = null }
  if (!response.ok) throw new Error(data?.message || `Request failed (${response.status}). Please try again.`)
  return data
}

export const postJson = (path, body) => request(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) })
