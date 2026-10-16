import { useEffect, useState } from 'react'

const API = import.meta.env.VITE_API_BASE_URL || '/api'
const SKILLS = ['JAVA', 'SPRING_BOOT', 'REST_API', 'SQL', 'POSTGRESQL', 'REACT', 'DOCKER', 'TESTING', 'GIT']
const NEXT = {
  JAVA: 'Add a small Java feature with clear classes and explain its design in the README.',
  SPRING_BOOT: 'Build a Spring Boot endpoint and commit the controller with a focused test.',
  REST_API: 'Add a documented API route with request validation and an example response.',
  SQL: 'Add a real query or database backed feature and test it with sample data.',
  POSTGRESQL: 'Configure PostgreSQL for a feature and explain its schema in the README.',
  REACT: 'Build a React view with state and a user interaction, then commit the source.',
  DOCKER: 'Add a working Dockerfile and document how to run the project with it.',
  TESTING: 'Add a meaningful test that checks behavior or handles a failure case.',
  GIT: 'Document your repository workflow and make focused, descriptive commits.'
}
const pretty = value => value.replaceAll('_', ' ')
const sameSkills = (left, right) => left.length === right.length && left.every(skill => right.includes(skill))
const historyPath = (jobId, repository) => `/assessments/history?jobId=${encodeURIComponent(jobId)}&repository=${encodeURIComponent(repository)}`
const latestPath = (jobId, repository) => `/assessments/latest?jobId=${encodeURIComponent(jobId)}&repository=${encodeURIComponent(repository)}`

async function request(path, options) {
  let response
  try { response = await fetch(`${API}${path}`, options) }
  catch { throw new Error('Cannot reach the backend. Start Spring Boot on port 8081 and try again.') }
  const text = await response.text()
  let data
  try { data = text ? JSON.parse(text) : null } catch { data = null }
  if (!response.ok) throw new Error(data?.message || `Request failed (${response.status}). Please try again.`)
  return data
}

export default function App() {
  const [repository, setRepository] = useState(() => localStorage.getItem('proofpath.repository') || '')
  const [branch, setBranch] = useState('')
  const [repoDetails, setRepoDetails] = useState(null)
  const [savedJobs, setSavedJobs] = useState([])
  const [job, setJob] = useState(null)
  const [selectedSkills, setSelectedSkills] = useState([])
  const [assessment, setAssessment] = useState(null)
  const [history, setHistory] = useState([])
  const [busy, setBusy] = useState('')
  const [watching, setWatching] = useState(false)
  const [installWebhook, setInstallWebhook] = useState(false)
  const [message, setMessage] = useState('Choose a job source and a GitHub repository to begin.')
  const [source, setSource] = useState({ title: '', company: '', sourceUrl: '', postingText: '' })

  useEffect(() => {
    request('/jobs').then(items => {
      setSavedJobs(items)
      const previous = items.find(item => item.id === localStorage.getItem('proofpath.jobId'))
      if (previous) { setJob(previous); setSelectedSkills(previous.requiredSkills) }
    }).catch(error => setMessage(error.message))
  }, [])

  useEffect(() => {
    localStorage.setItem('proofpath.repository', repository)
    if (!job || !repository.trim()) { setHistory([]); return }
    let active = true
    request(historyPath(job.id, repository.trim()))
      .then(async items => {
        if (!active) return
        setHistory(items)
        let latest = null
        try { latest = await request(latestPath(job.id, repository.trim())) } catch { /* No current assessment yet. */ }
        if (!active) return
        const current = latest && sameSkills(latest.job.requiredSkills, job.requiredSkills) ? latest : null
        setAssessment(current)
        if (items.length && !current) setMessage('The job requirements changed. Rescan to see an updated comparison.')
        if (current) request(`/repositories/watch?repository=${encodeURIComponent(repository.trim())}`)
          .then(status => { if (active && status.branch === current.portfolio.branch) setWatching(true) })
          .catch(() => {})
      })
      .catch(() => { if (active) setHistory([]) })
    return () => { active = false }
  }, [job?.id, repository])

  useEffect(() => {
    if (!watching || !job || !repository.trim()) return undefined
    const refresh = async () => {
      try {
        const latest = await request(latestPath(job.id, repository.trim()))
        if (latest.portfolio.revision !== assessment?.portfolio.revision) {
          setAssessment(latest)
          setHistory(await request(historyPath(job.id, repository.trim())))
          setMessage('Repository changed. Your assessment has been updated.')
        }
      } catch (error) { setMessage(`Could not refresh: ${error.message}`) }
    }
    const timer = window.setInterval(refresh, 10_000)
    return () => window.clearInterval(timer)
  }, [watching, job?.id, repository, assessment?.portfolio.revision])

  function chooseJob(next) {
    setJob(next); setSelectedSkills(next.requiredSkills); setAssessment(null); setHistory([]); setWatching(false)
    localStorage.setItem('proofpath.jobId', next.id)
  }
  async function loadDemo() {
    try { chooseJob(await request('/jobs/demo')); setMessage('Demo role loaded. Use an employer posting for a personal assessment.') }
    catch (error) { setMessage(error.message) }
  }
  async function discoverJob(event) {
    event.preventDefault(); setBusy('Reading the job posting…')
    try {
      const found = await request('/jobs/discover', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(source) })
      chooseJob(found); setSavedJobs(items => [found, ...items]); setMessage('Job loaded. Review the detected skills before assessing.')
    } catch (error) { setMessage(error.message) } finally { setBusy('') }
  }
  async function saveRequirements() {
    setBusy('Saving requirements…')
    try {
      const updated = await request(`/jobs/${encodeURIComponent(job.id)}/requirements`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ skills: selectedSkills }) })
      chooseJob(updated); setSavedJobs(items => items.map(item => item.id === updated.id ? updated : item))
      setMessage('Requirements saved. Run a new assessment to compare against the edited list.')
    } catch (error) { setMessage(error.message) } finally { setBusy('') }
  }
  async function resolveRepository() {
    if (!repository.trim()) return setMessage('Enter a repository as owner/name.')
    setBusy('Checking repository…')
    try {
      const resolved = await request(`/repositories/resolve?repository=${encodeURIComponent(repository.trim())}`)
      setRepoDetails(resolved); setRepository(resolved.fullName); setMessage(`Found ${resolved.fullName}. Default branch: ${resolved.defaultBranch}.`)
    } catch (error) { setRepoDetails(null); setMessage(error.message) } finally { setBusy('') }
  }
  async function assess() {
    if (!job || !repository.trim()) return setMessage('Choose a job and enter a repository first.')
    if (!sameSkills(selectedSkills, job.requiredSkills)) return setMessage('Save your requirement changes before assessing.')
    setBusy('Scanning code and comparing evidence…')
    try {
      const result = await request('/assessments', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ jobId: job.id, repository: repository.trim(), branch }) })
      setRepository(result.portfolio.repository); setAssessment(result); setHistory(await request(historyPath(job.id, result.portfolio.repository)))
      setMessage(`Assessment complete at commit ${result.portfolio.revision.slice(0, 7)}.`)
    } catch (error) { setMessage(error.message) } finally { setBusy('') }
  }
  async function watch() {
    if (!assessment) return setMessage('Assess the repository once before watching it.')
    setBusy('Starting change tracking…')
    try {
      const result = await request('/repositories/watch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ repository: repository.trim(), branch, installGitHubWebhook: installWebhook }) })
      setRepository(result.repository); setWatching(true); setMessage(result.message)
    } catch (error) { setMessage(error.message) } finally { setBusy('') }
  }

  const proved = assessment?.gaps.filter(gap => gap.status === 'DEMONSTRATED').length || 0
  const earlier = history.find(item => item.portfolio.revision !== assessment?.portfolio.revision)
  const newlyProved = assessment?.gaps.filter(gap => gap.status === 'DEMONSTRATED' && earlier?.gaps.find(old => old.skill === gap.skill)?.status !== 'DEMONSTRATED') || []
  const coverage = assessment?.portfolio

  return <main className="page">
    <header><p className="eyebrow">PROOFPATH · CAREER EVIDENCE</p><h1>Show what your <em>code proves.</em></h1><p className="subtitle">Compare a job posting with evidence in your GitHub repository, then see what to build next.</p></header>
    <p className="notice" role="status" aria-live="polite">{busy || message}</p>
    <section className="grid">
      <article className="panel"><h2>1. Choose a job</h2><p className="hint">Paste a public employer posting. Review the detected requirements before you scan.</p>
        {savedJobs.length > 0 && <label>Previous jobs<select value={savedJobs.some(item => item.id === job?.id) ? job.id : ''} onChange={event => { const next = savedJobs.find(item => item.id === event.target.value); if (next) chooseJob(next) }}><option value="">Select a saved job</option>{savedJobs.map(item => <option key={item.id} value={item.id}>{item.title} · {item.company}</option>)}</select></label>}
        <form onSubmit={discoverJob}><label>Role<input required value={source.title} onChange={event => setSource({ ...source, title: event.target.value })} placeholder="Backend Engineer Intern" /></label><label>Company<input required value={source.company} onChange={event => setSource({ ...source, company: event.target.value })} placeholder="Company name" /></label><label>Job posting URL<input required type="url" value={source.sourceUrl} onChange={event => setSource({ ...source, sourceUrl: event.target.value })} placeholder="https://careers.example.com/job" /></label><label>Posting text (optional)<textarea value={source.postingText} onChange={event => setSource({ ...source, postingText: event.target.value })} placeholder="If the website blocks fetching, paste the job description here." rows="4" /></label><button disabled={!!busy}>Read job posting</button></form>
        <button className="linkButton" type="button" onClick={loadDemo}>Try the demo role</button>
        {job && <div className="source"><strong>{job.title} · {job.company}</strong><a href={job.sourceUrl} target="_blank" rel="noreferrer">View source ↗</a><p className="hint">Select only the requirements relevant to this role.</p><div className="skillChoices">{SKILLS.map(skill => <label className="check" key={skill}><input type="checkbox" checked={selectedSkills.includes(skill)} onChange={() => setSelectedSkills(current => current.includes(skill) ? current.filter(item => item !== skill) : [...current, skill])} />{pretty(skill)}</label>)}</div><button type="button" className="secondary" disabled={!!busy || selectedSkills.length === 0 || sameSkills(selectedSkills, job.requiredSkills)} onClick={saveRequirements}>Save requirements</button></div>}
      </article>
      <article className="panel"><h2>2. Check your repository</h2><p className="hint">Enter a public GitHub repository or paste its URL. Leave branch empty to use its default branch.</p><label>Repository<input value={repository} onChange={event => { setRepository(event.target.value); setRepoDetails(null); setWatching(false) }} placeholder="owner/repository" /></label><label>Branch (optional)<input value={branch} onChange={event => { setBranch(event.target.value); setWatching(false) }} placeholder="Use repository default" /></label><button className="secondary" type="button" disabled={!!busy} onClick={resolveRepository}>Check repository</button>{repoDetails && <p className="hint">Found <a href={repoDetails.htmlUrl} target="_blank" rel="noreferrer">{repoDetails.fullName}</a> · default branch: {repoDetails.defaultBranch}</p>}
        <div className="actions"><button type="button" onClick={assess} disabled={!!busy || !job || !repository.trim()}>{assessment ? 'Rescan now' : 'Assess repository'}</button><button type="button" className="secondary" onClick={watch} disabled={!!busy || !assessment}>Watch changes</button></div>
        <details><summary>Advanced: GitHub push webhook</summary><p className="hint">The normal watch checks for changes every minute. A webhook can update sooner when the backend has a public HTTPS URL and a GitHub token.</p><label className="check"><input type="checkbox" checked={installWebhook} onChange={event => setInstallWebhook(event.target.checked)} />Install a GitHub webhook</label></details>
        {watching && <p className="watching">Watching for changes. This page checks for updated results every 10 seconds.</p>}
      </article>
    </section>
    {assessment && <section className="panel results"><h2>3. Your assessment</h2><p className="score">{proved} of {assessment.gaps.length} requirements demonstrated</p><p className="hint">A missing signal means only that this scan did not find evidence. It does not mean you lack the skill.</p>
      <div className="coverage"><p><strong>Scan coverage:</strong> {coverage.scannedFiles} relevant files read{coverage.skippedFiles > 0 ? `, ${coverage.skippedFiles} skipped` : ''}{coverage.treeTruncated ? '; GitHub returned a partial file tree' : ''}.</p><p className="revision">Branch {coverage.branch} · commit <code>{coverage.revision}</code> · {new Date(coverage.scannedAt).toLocaleString()}</p>{(coverage.skippedFiles > 0 || coverage.treeTruncated) && <p className="hint">Some files were not inspected, so missing evidence may be incomplete.</p>}</div>
      {earlier && assessment.portfolio.revision === history[0]?.portfolio.revision && <p className="watching">Since the previous commit: {newlyProved.length ? `${newlyProved.map(gap => pretty(gap.skill)).join(', ')} now demonstrated.` : 'No new requirement demonstrated yet.'}</p>}
      <div className="gapGrid">{assessment.gaps.map(gap => <article className={`gap ${gap.status === 'DEMONSTRATED' ? 'proved' : 'unknown'}`} key={gap.skill}><h3>{pretty(gap.skill)}</h3><span>{gap.status === 'DEMONSTRATED' ? 'Evidence found' : 'Not demonstrated in this scan'}</span><p>{gap.explanation}</p>{gap.status !== 'DEMONSTRATED' && <p><strong>Try next:</strong> {NEXT[gap.skill]}</p>}{gap.evidence.map(evidence => <div className="evidence" key={evidence.url}><a href={evidence.url} target="_blank" rel="noreferrer">{evidence.path} ↗</a><code>{evidence.snippet}</code></div>)}</article>)}</div>
      {history.length > 1 && <div className="history"><h3>Previous scans</h3><p className="hint">Open an older scan to see how your evidence changed.</p><div className="historyItems">{history.map((item, index) => <button type="button" className={assessment.portfolio.scannedAt === item.portfolio.scannedAt ? 'activeHistory' : 'secondary'} key={`${item.portfolio.scannedAt}-${index}`} onClick={() => setAssessment(item)}>{new Date(item.portfolio.scannedAt).toLocaleString()} · {item.gaps.filter(gap => gap.status === 'DEMONSTRATED').length}/{item.gaps.length}</button>)}</div></div>}
    </section>}
  </main>
}
