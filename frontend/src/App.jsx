import { useEffect, useState } from 'react'

// Empty in local development: Vite proxies /api to Spring Boot. Set VITE_API_BASE_URL for deployment.
const API = import.meta.env.VITE_API_BASE_URL || '/api'
const pretty = value => value.replaceAll('_', ' ')

async function request(path, options) {
  const response = await fetch(`${API}${path}`, options)
  if (!response.ok) throw new Error((await response.text()) || `Request failed (${response.status})`)
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

export default function App() {
  const [repository, setRepository] = useState('')
  const [branch, setBranch] = useState('main')
  const [job, setJob] = useState(null)
  const [assessment, setAssessment] = useState(null)
  const [loading, setLoading] = useState(false)
  const [watching, setWatching] = useState(false)
  const [installWebhook, setInstallWebhook] = useState(false)
  const [message, setMessage] = useState('Choose a real job source, then inspect a public GitHub repository.')
  const [source, setSource] = useState({ title: '', company: '', sourceUrl: '' })

  useEffect(() => {
    if (!watching || !job || !repository.trim()) return undefined
    const refresh = async () => {
      try {
        const result = await request(`/assessments/latest?jobId=${encodeURIComponent(job.id)}&repository=${encodeURIComponent(repository.trim())}`)
        setAssessment(previous => previous?.portfolio.revision === result.portfolio.revision ? previous : result)
      } catch { /* Keep the last evidence visible if the background refresh fails. */ }
    }
    refresh()
    const timer = window.setInterval(refresh, 10_000)
    return () => window.clearInterval(timer)
  }, [watching, job, repository])

  async function loadDemo() {
    try { setJob(await request('/jobs/demo')); setMessage('Demo role loaded. Replace it with a real employer source before presenting.') }
    catch (error) { setMessage(error.message) }
  }

  async function discoverJob(event) {
    event.preventDefault()
    setLoading(true)
    try {
      const discovered = await request('/jobs/discover', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(source) })
      setJob(discovered); setAssessment(null); setMessage(`Saved requirements from ${discovered.company}.`)
    } catch (error) { setMessage(error.message) } finally { setLoading(false) }
  }

  async function assess() {
    if (!job || !repository.trim()) { setMessage('Add a job source and repository first.'); return }
    setLoading(true)
    try {
      const result = await request('/assessments', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ jobId: job.id, repository: repository.trim(), branch }) })
      setAssessment(result); setMessage(`Evidence found at commit ${result.portfolio.revision.slice(0, 7)}.`)
    } catch (error) { setMessage(error.message) } finally { setLoading(false) }
  }

  async function watch() {
    if (!repository.trim()) return setMessage('Enter a repository first.')
    try {
      const result = await request('/repositories/watch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ repository: repository.trim(), branch, installGitHubWebhook: installWebhook }) })
      setWatching(true)
      setMessage(result.message)
    } catch (error) { setMessage(error.message) }
  }

  return <main className="page">
    <header><p className="eyebrow">W-PS-2 · WORCFLOW</p><h1>Show what your <em>code proves.</em></h1><p className="subtitle">ProofPath compares job requirements with observable portfolio evidence. It never treats an absent signal as proof you lack a skill.</p></header>
    <p className="notice" aria-live="polite">{message}</p>
    <section className="grid">
      <article className="panel"><h2>1. Job requirement source</h2><p className="hint">Use the original employer job or internship page. Its URL and fetched text are saved with the assessment.</p>
        <form onSubmit={discoverJob}><label>Role<input required value={source.title} onChange={e => setSource({...source, title:e.target.value})} placeholder="Backend Software Engineer Intern" /></label><label>Company<input required value={source.company} onChange={e => setSource({...source, company:e.target.value})} placeholder="Company name" /></label><label>Job source URL<input required type="url" value={source.sourceUrl} onChange={e => setSource({...source, sourceUrl:e.target.value})} placeholder="https://careers.company.com/..." /></label><button disabled={loading}>Discover requirements</button></form>
        <button className="linkButton" onClick={loadDemo}>Load transparent demo role</button>
        {job && <div className="source"><strong>{job.title} · {job.company}</strong><a href={job.sourceUrl} target="_blank" rel="noreferrer">Open source ↗</a><p>Detected requirements: {job.requiredSkills.map(pretty).join(', ') || 'No supported skills matched yet'}</p></div>}
      </article>
      <article className="panel"><h2>2. Portfolio evidence</h2><p className="hint">Public repositories work with no token. Set <code>GITHUB_TOKEN</code> on the backend for private repositories or GitHub webhook creation.</p><label>Repository<input value={repository} onChange={e => { setRepository(e.target.value); setWatching(false) }} placeholder="owner/repository" /></label><label>Branch<input value={branch} onChange={e => { setBranch(e.target.value); setWatching(false) }} placeholder="Leave as main, or use the repository default" /></label><label className="check"><input type="checkbox" checked={installWebhook} onChange={e => setInstallWebhook(e.target.checked)} /> Create a real GitHub push webhook (requires backend HTTPS callback configuration)</label><div className="actions"><button onClick={assess} disabled={loading}>{loading ? 'Scanning…' : 'Assess repository'}</button><button className="secondary" onClick={watch}>Watch changes</button></div>{watching && <p className="watching">Watching: the UI refreshes every 10 seconds after a repository change.</p>}{assessment && <p className="revision">Scanned <code>{assessment.portfolio.revision}</code> at {new Date(assessment.portfolio.scannedAt).toLocaleString()}.</p>}</article>
    </section>
    {assessment && <section className="panel results"><h2>3. Evidence-backed assessment</h2><p className="hint">Every green item includes a file at the exact scanned Git commit. Yellow means only “not demonstrated in this scan.”</p><div className="gapGrid">{assessment.gaps.map(gap => <article className={`gap ${gap.status === 'DEMONSTRATED' ? 'proved' : 'unknown'}`} key={gap.skill}><h3>{pretty(gap.skill)}</h3><span>{pretty(gap.status)}</span><p>{gap.explanation}</p>{gap.evidence.map(evidence => <div className="evidence" key={evidence.url}><a href={evidence.url} target="_blank" rel="noreferrer">{evidence.path} ↗</a><code>{evidence.snippet}</code></div>)}</article>)}</div></section>}
  </main>
}
