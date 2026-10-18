import { forwardRef, useEffect, useRef, useState } from 'react'
import { AnimatePresence, LazyMotion, MotionConfig, m } from 'framer-motion'
import { CountUp, cardIn, ease, fadeUp, spotlight, spring, stagger } from './motion.jsx'
import { GrowthPanel, LearningPlan } from './Growth.jsx'
import { ActivityFeed, PortfolioPanel } from './Portfolio.jsx'

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
const STATUS = {
  DEMONSTRATED: { tone: 'good', label: 'Demonstrated' },
  PARTIAL: { tone: 'warn', label: 'Partial evidence' },
  NOT_DEMONSTRATED: { tone: 'gap', label: 'Not shown in this scan' }
}
const loadFeatures = () => import('./motionFeatures.js').then(module => module.default)
const LOOP = ['Job requirements', 'Repository evidence', 'Gaps explained', 'Code change', 'Updated assessment']
const pretty = value => value.replaceAll('_', ' ')
const statusOf = gap => STATUS[gap.status] || STATUS.NOT_DEMONSTRATED
const byAi = value => !!value && value !== 'rules'
const aiName = value => value?.split(':').slice(1).join(':')
const shortSha = sha => sha?.slice(0, 7)
const sameSkills = (left, right) => left.length === right.length && left.every(skill => right.includes(skill))
const historyPath = (jobId, repository) => `/assessments/history?jobId=${encodeURIComponent(jobId)}&repository=${encodeURIComponent(repository)}`
const latestPath = (jobId, repository) => `/assessments/latest?jobId=${encodeURIComponent(jobId)}&repository=${encodeURIComponent(repository)}`
const BOARDS = [['GREENHOUSE', 'Greenhouse', 'stripe'], ['LEVER', 'Lever', 'palantir'], ['ASHBY', 'Ashby', 'notion']]
const hostOf = url => { try { return new URL(url).hostname.toLowerCase() } catch { return '' } }
/** Recognises the job site from a posting link: some must be pasted, job-board links are read through their API. */
function detectSite(url) {
  const host = hostOf(url)
  if (!host) return null
  if (/(^|\.)linkedin\.com$|^lnkd\.in$/.test(host)) return { label: 'LinkedIn', needsText: true }
  if (/(^|\.)indeed\.[a-z.]+$/.test(host)) return { label: 'Indeed', needsText: true }
  if (/(^|\.)naukri\.com$/.test(host)) return { label: 'Naukri', needsText: true }
  if (/(^|\.)glassdoor\.[a-z.]+$/.test(host)) return { label: 'Glassdoor', needsText: true }
  if (/(^|\.)greenhouse\.io$/.test(host)) return { label: 'Greenhouse', api: true }
  if (/^jobs(\.eu)?\.lever\.co$/.test(host)) return { label: 'Lever', api: true }
  if (host === 'jobs.ashbyhq.com') return { label: 'Ashby', api: true }
  return null
}
const STATUS_TEXT = { DEMONSTRATED: 'Demonstrated', PARTIAL: 'Partial', NOT_DEMONSTRATED: 'Not demonstrated in this scan' }
const md = value => String(value ?? '').replace(/\|/g, '\\|').replace(/\s+/g, ' ').trim()

/** Builds the hackathon's "demo evidence" as Markdown: job source, repository evidence, gaps, the change and the revised assessment. */
function evidenceReport(assessment, history) {
  const { job, portfolio, gaps, change } = assessment
  const earlier = history.find(item => item.portfolio.revision !== portfolio.revision)
  const lines = []
  lines.push('# ProofPath evidence report', '')
  lines.push(`Generated ${new Date().toLocaleString()} · ${byAi(assessment.assessedBy) ? `AI reviewed (${aiName(assessment.assessedBy)})` : 'keyword rules'}`, '')
  lines.push('## 1. Job source', '')
  lines.push(`- **Role:** ${md(job.title)} at ${md(job.company)}`, `- **Source:** ${job.sourceUrl}`, `- **Requirements:** ${job.requiredSkills.map(pretty).join(', ')}`)
  for (const quote of job.requirementQuotes || []) lines.push(`  - ${pretty(quote.skill)} (${quote.importance.toLowerCase()}): “${md(quote.quote)}”`)
  lines.push('', '## 2. Repository evidence', '')
  lines.push(`- **Repository:** https://github.com/${portfolio.repository} (branch \`${portfolio.branch}\`)`)
  lines.push(`- **Commit:** [${shortSha(portfolio.revision)}](https://github.com/${portfolio.repository}/commit/${portfolio.revision}) scanned ${new Date(portfolio.scannedAt).toLocaleString()}`)
  lines.push(`- **Coverage:** ${portfolio.scannedFiles} files read${portfolio.skippedFiles ? `, ${portfolio.skippedFiles} skipped` : ''}${portfolio.treeTruncated ? ', partial tree' : ''}`)
  lines.push('', '## 3. Gaps', '', `Role readiness: **${readiness(gaps)}%**`, '', '| Skill | Verdict | Confidence | Evidence | Next step |', '|---|---|---|---|---|')
  for (const gap of gaps) {
    const evidence = gap.status === 'NOT_DEMONSTRATED' ? '—' : gap.evidence.map(e => `[${md(e.path)}](${e.url})`).join('<br>') || '—'
    lines.push(`| ${pretty(gap.skill)} | ${STATUS_TEXT[gap.status] || gap.status} | ${gap.confidence ? gap.confidence.toLowerCase() : '—'} | ${evidence} | ${gap.status === 'DEMONSTRATED' ? '—' : md(gap.nextStep || NEXT[gap.skill])} |`)
  }
  if (assessment.summary && byAi(assessment.assessedBy)) lines.push('', `> ${md(assessment.summary)}`)
  if (change) {
    lines.push('', '## 4. Repository change', '')
    lines.push(`Commit [${shortSha(change.baseRevision)}](https://github.com/${portfolio.repository}/commit/${change.baseRevision}) → [${shortSha(change.headRevision)}](https://github.com/${portfolio.repository}/commit/${change.headRevision}) · [compare](https://github.com/${portfolio.repository}/compare/${change.baseRevision}...${change.headRevision})`, '')
    for (const file of change.files) lines.push(`- \`${file.status}\` [${md(file.path)}](${file.url})`)
    lines.push('', `- **Reassessed skills:** ${change.reassessedSkills.length ? change.reassessedSkills.map(pretty).join(', ') : 'none (no evidence files changed)'}`)
    lines.push(`- **Unchanged, carried over:** ${change.carriedOverSkills.length ? change.carriedOverSkills.map(pretty).join(', ') : 'none'}`)
    if (!change.complete) lines.push('- GitHub could not list every change, so every skill was reassessed.')
  }
  if (earlier) {
    lines.push('', '## 5. Revised assessment', '', `| Skill | Before (${shortSha(earlier.portfolio.revision)}) | After (${shortSha(portfolio.revision)}) |`, '|---|---|---|')
    for (const gap of gaps) {
      const before = earlier.gaps.find(old => old.skill === gap.skill)
      lines.push(`| ${pretty(gap.skill)} | ${STATUS_TEXT[before?.status] || '—'} | ${STATUS_TEXT[gap.status] || gap.status}${before && before.status !== gap.status ? ' ✅' : ''} |`)
    }
    lines.push('', `Readiness: ${readiness(earlier.gaps)}% → **${readiness(gaps)}%**`)
  }
  lines.push('', '---', '', '_A missing signal means "not demonstrated in this scope", not proof that the student lacks the skill._', '')
  return lines.join('\n')
}

function download(name, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }))
  const link = Object.assign(document.createElement('a'), { href: url, download: name })
  document.body.appendChild(link); link.click(); link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

const readiness = gaps => gaps.length ? Math.round(100 * gaps.reduce((sum, gap) => sum + (gap.status === 'DEMONSTRATED' ? 1 : gap.status === 'PARTIAL' ? 0.5 : 0), 0) / gaps.length) : 0

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

const ICONS = {
  logo: <><path d="M5 19V5h7a4.5 4.5 0 0 1 0 9H5" /><path d="m13 17 2.5 2.5L21 14" /></>,
  spark: <path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3zM19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15z" />,
  briefcase: <><rect x="3" y="7" width="18" height="13" rx="2" /><path d="M8 7V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M3 13h18" /></>,
  repo: <><path d="M5 4.5A2.5 2.5 0 0 1 7.5 2H19v17H7.5A2.5 2.5 0 0 0 5 21.5z" /><path d="M5 19.5V4.5M9 7h6" /></>,
  link: <><path d="M14 4h6v6M20 4l-9 9" /><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" /></>,
  check: <path d="m5 12.5 4.5 4.5L19 7.5" />,
  half: <><circle cx="12" cy="12" r="8" /><path d="M12 4a8 8 0 0 1 0 16z" fill="currentColor" /></>,
  minus: <><circle cx="12" cy="12" r="8" /><path d="M8.5 12h7" /></>,
  eye: <><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" /><circle cx="12" cy="12" r="3" /></>,
  commit: <><circle cx="12" cy="12" r="3.5" /><path d="M2 12h6.5M15.5 12H22" /></>,
  arrow: <path d="M5 12h14M13 6l6 6-6 6" />,
  quote: <path d="M7 7h4v4c0 3-1.5 5-4 6M15 7h4v4c0 3-1.5 5-4 6" />,
  file: <><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /></>,
  bolt: <path d="M13 2 4 14h7l-1 8 9-12h-7z" />,
  github: <path d="M9 19c-4.3 1.4-4.3-2.5-6-3m12 5v-3.5c0-1 .1-1.4-.5-2 2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2 4.2 4.2 0 0 0-.1-3.2s-1.1-.3-3.5 1.3a12.3 12.3 0 0 0-6.2 0C6.5 2.8 5.4 3.1 5.4 3.1a4.2 4.2 0 0 0-.1 3.2A4.6 4.6 0 0 0 4 9.5c0 4.6 2.7 5.7 5.5 6-.6.6-.6 1.2-.5 2V21" />,
  target: <><circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="5" /><circle cx="12" cy="12" r="1.5" /></>,
  lock: <><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></>
}
function Icon({ name, size = 18 }) {
  return <svg className="icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{ICONS[name]}</svg>
}

function ScoreRing({ value }) {
  const radius = 52, circumference = 2 * Math.PI * radius
  return <div className="ring" role="img" aria-label={`Readiness ${value} percent`}>
    <svg viewBox="0 0 120 120"><defs><linearGradient id="ringGradient" x1="0" x2="1" y1="0" y2="1"><stop offset="0%" stopColor="var(--accent)" /><stop offset="100%" stopColor="var(--accent-2)" /></linearGradient></defs>
      <circle cx="60" cy="60" r={radius} className="ringTrack" />
      <m.circle cx="60" cy="60" r={radius} className="ringValue" strokeDasharray={circumference}
        initial={{ strokeDashoffset: circumference }} animate={{ strokeDashoffset: circumference * (1 - value / 100) }}
        transition={{ duration: 1.3, ease }} />
    </svg>
    <div className="ringLabel"><strong><CountUp value={value} /><small>%</small></strong><span>role readiness</span></div>
  </div>
}

function Confidence({ level }) {
  if (!level) return null
  const filled = { HIGH: 3, MEDIUM: 2, LOW: 1 }[level] || 0
  return <span className="confidence" title={`${level.toLowerCase()} confidence`}>{[1, 2, 3].map(bar =>
    <m.i key={bar} className={bar <= filled ? 'on' : ''} initial={{ scaleY: 0 }} animate={{ scaleY: 1 }} transition={{ delay: 0.35 + bar * 0.08, ...spring }} />)}<em>{level.toLowerCase()}</em></span>
}

const SkillCard = forwardRef(function SkillCard({ gap, change, recheck, changedPaths, learning, fix }, ref) {
  const status = statusOf(gap)
  const rejected = gap.status === 'NOT_DEMONSTRATED' && gap.evidence.length > 0
  return <m.article ref={ref} layout variants={cardIn} initial="hidden" animate="show" exit="exit" whileHover={{ y: -5 }} transition={spring}
    className={`skill tone-${status.tone} ${change ? 'changed' : ''}`} onPointerMove={spotlight}>
    <header>
      <m.span className="skillIcon" initial={{ scale: 0, rotate: -30 }} animate={{ scale: 1, rotate: 0 }} transition={{ delay: 0.2, ...spring }}>
        <Icon name={gap.status === 'DEMONSTRATED' ? 'check' : gap.status === 'PARTIAL' ? 'half' : 'minus'} />
      </m.span>
      <div><h3>{pretty(gap.skill)}</h3><span className="chip">{status.label}</span>{change && <m.span className="chip chip-new" initial={{ scale: 0 }} animate={{ scale: [0, 1.25, 1] }} transition={{ delay: 0.5, duration: 0.5 }}>{change}</m.span>}{recheck && <span className={`chip chip-${recheck}`} title={recheck === 'rechecked' ? 'A file behind this skill changed, so it was reassessed' : 'No file behind this skill changed, so its verdict was kept'}>{recheck === 'rechecked' ? 'Re-checked' : 'Unchanged'}</span>}</div>
      <Confidence level={gap.confidence} />
    </header>
    <p className="explain">{gap.explanation}</p>
    {gap.status !== 'DEMONSTRATED' && <div className="nextStep"><Icon name="bolt" size={15} /><p><strong>Build next</strong>{gap.nextStep || NEXT[gap.skill]}</p></div>}
    {gap.status !== 'DEMONSTRATED' && fix && <div className="fixRow">
      {fix.issue
        ? <a className="secondary linkButton" href={fix.issue.url} target="_blank" rel="noreferrer"><Icon name="github" size={15} />{fix.issue.alreadyOpen ? 'Issue already open' : 'Issue created'} #{fix.issue.number}</a>
        : <button type="button" className="primary" disabled={fix.busy} onClick={() => fix.run(gap)}><Icon name="github" size={15} />Fix this gap</button>}
      {fix.issue
        ? <span className="muted small">Close it with a commit and ProofPath re-checks {pretty(gap.skill)} on its own.</span>
        : fix.hint}
    </div>}
    {gap.status !== 'DEMONSTRATED' && learning && <LearningPlan path={learning} who={fix?.who || 'guest'} />}
    {gap.evidence.length > 0 && <details className="evidenceList" open={gap.status !== 'NOT_DEMONSTRATED'}>
      <summary>{rejected ? `${gap.evidence.length} keyword signal${gap.evidence.length > 1 ? 's' : ''} reviewed, not accepted` : `${gap.evidence.length} cited file${gap.evidence.length > 1 ? 's' : ''}`}</summary>
      {gap.evidence.map(evidence => <div className="evidence" key={evidence.url}>
        <a href={evidence.url} target="_blank" rel="noreferrer"><Icon name="file" size={14} /><span>{evidence.path}</span>{changedPaths?.has(evidence.path) && <em className="changedTag">changed in this commit</em>}</a>
        {evidence.snippet && <code>{evidence.snippet}</code>}
      </div>)}
    </details>}
  </m.article>
})

function SkeletonResults() {
  return <m.section className="results" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} aria-hidden="true">
    <div className="card skeletonOverview"><div className="sk sk-ring" /><div className="skLines"><div className="sk sk-line w40" /><div className="sk sk-line w70" /><div className="sk sk-line w90" /></div></div>
    <div className="skills">{[0, 1, 2].map(index => <div key={index} className="skill skeleton"><div className="sk sk-line w40" /><div className="sk sk-line w90" /><div className="sk sk-line w70" /><div className="sk sk-block" /></div>)}</div>
  </m.section>
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
  const [message, setMessage] = useState({ text: 'Choose a job posting and a GitHub repository to begin.', tone: 'info' })
  const [source, setSource] = useState({ title: '', company: '', sourceUrl: '', postingText: '' })
  const [boardQuery, setBoardQuery] = useState({ source: 'GREENHOUSE', board: '', q: '' })
  const [boardResults, setBoardResults] = useState(null)
  const [ai, setAi] = useState(null)
  const [auth, setAuth] = useState({ configured: false, user: null })
  const [paths, setPaths] = useState({})
  const [issues, setIssues] = useState({})
  const [fixing, setFixing] = useState(false)
  const [authChecked, setAuthChecked] = useState(false)
  const [guest, setGuest] = useState(() => { try { return sessionStorage.getItem('pp_guest') === '1' } catch { return false } })
  const [form, setForm] = useState({ mode: 'signin', name: '', email: '', password: '', busy: false, error: '', notice: '' })
  const [resent, setResent] = useState(false)
  const continueAsGuest = () => { try { sessionStorage.setItem('pp_guest', '1') } catch { /* private mode */ } setGuest(true) }
  const [myRepos, setMyRepos] = useState([])
  const [ready, setReady] = useState({ source: 'GREENHOUSE', boards: '', q: '', results: null })
  const [filter, setFilter] = useState('ALL')
  const [justUpdated, setJustUpdated] = useState(false)
  const [pending, setPending] = useState(null)
  const pendingRef = useRef(null)

  const say = (text, tone = 'info') => setMessage({ text, tone })
  const fail = error => say(error.message, 'error')

  useEffect(() => { request('/ai/status').then(setAi).catch(() => setAi(null)) }, [])

  useEffect(() => { request('/learning-paths').then(list => setPaths(Object.fromEntries(list.map(path => [path.skill, path])))).catch(() => {}) }, [])

  // GitHub sign-in: status, the result of the OAuth redirect, and the user's repositories.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    if (params.has('signedIn')) say('Signed in with GitHub. Pick one of your repositories below.', 'success')
    if (params.has('authError')) say(params.get('authError'), 'error')
    if (params.has('signedIn') || params.has('authError')) window.history.replaceState({}, '', window.location.pathname)
    request('/auth/status').then(status => {
      setAuth(status)
      if (status.user) request('/me/repos').then(setMyRepos).catch(error => say(`Could not list your repositories: ${error.message}`, 'warn'))
    }).catch(() => {}).finally(() => setAuthChecked(true))
  }, [])

  useEffect(() => {
    request('/jobs').then(items => {
      setSavedJobs(items)
      const previous = items.find(item => item.id === localStorage.getItem('proofpath.jobId'))
      if (previous) { setJob(previous); setSelectedSkills(previous.requiredSkills) }
    }).catch(fail)
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
        if (items.length && !current) say('The job requirements changed. Rescan to see an updated comparison.', 'warn')
        if (current) request(`/repositories/watch?repository=${encodeURIComponent(repository.trim())}`)
          .then(status => { if (active && status.branch === current.portfolio.branch) setWatching(true) })
          .catch(() => {})
      })
      .catch(() => { if (active) setHistory([]) })
    return () => { active = false }
  }, [job?.id, repository])

  useEffect(() => {
    if (!watching || !job || !repository.trim()) return undefined
    // The watch status is a cheap local call: it reports a newly detected commit while the backend reassesses it,
    // and the last fully assessed commit once the update is ready.
    const refresh = async () => {
      try {
        const status = await request(`/repositories/watch?repository=${encodeURIComponent(repository.trim())}`)
        if (status.pendingRevision && status.pendingRevision !== assessment?.portfolio.revision) {
          if (pendingRef.current !== status.pendingRevision) say(`New commit ${shortSha(status.pendingRevision)} detected. The AI is reassessing it…`, 'info')
          pendingRef.current = status.pendingRevision
          setPending(status.pendingRevision)
          return
        }
        if (!status.revision || status.revision === assessment?.portfolio.revision) { pendingRef.current = null; setPending(null); return }
        const latest = await request(latestPath(job.id, repository.trim()))
        if (latest.portfolio.revision !== assessment?.portfolio.revision) {
          setAssessment(latest)
          setHistory(await request(historyPath(job.id, repository.trim())))
          setJustUpdated(true)
          pendingRef.current = null; setPending(null)
          say(`Commit ${shortSha(latest.portfolio.revision)} reassessed automatically. See what changed below.`, 'success')
        }
      } catch (error) { say(`Could not refresh: ${error.message}`, 'error') }
    }
    const timer = window.setInterval(refresh, 4_000)
    return () => window.clearInterval(timer)
  }, [watching, job?.id, repository, assessment?.portfolio.revision])

  function chooseJob(next) {
    setJob(next); setSelectedSkills(next.requiredSkills); setAssessment(null); setHistory([]); setWatching(false); setJustUpdated(false); setPending(null)
    localStorage.setItem('proofpath.jobId', next.id)
  }
  async function searchBoard(event) {
    event.preventDefault(); setBusy('Searching the job board…')
    try {
      const params = new URLSearchParams({ source: boardQuery.source, board: boardQuery.board.trim(), q: boardQuery.q.trim() })
      const found = await request(`/jobs/boards?${params}`)
      setBoardResults(found)
      say(found.total ? `${found.total} open posting${found.total > 1 ? 's' : ''} at ${found.company}${found.total > found.jobs.length ? `; showing the first ${found.jobs.length}` : ''}. Pick one to import.` : `No postings at ${found.company} match "${boardQuery.q}".`, found.total ? 'success' : 'warn')
    } catch (error) { setBoardResults(null); fail(error) } finally { setBusy('') }
  }
  const issueKey = gap => `${job?.id}|${assessment?.portfolio.repository}|${gap.skill}`
  async function fixGap(gap) {
    if (!auth.user) return say('Sign in with GitHub first. ProofPath opens the issue in your own repository, as you.', 'warn')
    setFixing(true)
    try {
      const issue = await request('/gaps/issue', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ jobId: job.id, repository: assessment.portfolio.repository, skill: gap.skill }) })
      setIssues(current => ({ ...current, [issueKey(gap)]: issue }))
      if (issue.watching) setWatching(true)
      say(`${issue.alreadyOpen ? 'Issue already open' : 'Issue created'}: #${issue.number} in ${assessment.portfolio.repository}. Close it with a commit and ProofPath re-checks ${pretty(gap.skill)}.`, 'success')
    } catch (error) { fail(error) } finally { setFixing(false) }
  }
  const fixFor = gap => ({
    run: fixGap, busy: fixing, issue: issues[issueKey(gap)], who: auth.user?.login || 'guest',
    hint: !auth.user ? <span className="muted small">Sign in with GitHub to open this as an issue in your repository.</span>
      : auth.user.provider === 'email' ? <span className="muted small">Opening an issue needs a GitHub sign-in.</span>
      : !auth.user.issueAccess ? <a className="small" href={`${API}/auth/github/login?issues=true`}>Allow ProofPath to open issues</a> : null
  })
  async function submitAccount(event) {
    event.preventDefault()
    setForm(current => ({ ...current, busy: true, error: '', notice: '' }))
    try {
      if (form.mode === 'forgot') {
        const reply = await request('/auth/forgot', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: form.email }) })
        setForm(current => ({ ...current, busy: false, notice: reply.message }))
        return
      }
      await request(form.mode === 'signup' ? '/auth/register' : '/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: form.name, email: form.email, password: form.password })
      })
      setAuth(await request('/auth/status')); setMyRepos([])
      setForm(current => ({ ...current, password: '', busy: false }))
      request('/jobs').then(setSavedJobs).catch(() => {})
    } catch (error) { setForm(current => ({ ...current, busy: false, error: error.message })) }
  }
  async function signOut() {
    try { await request('/auth/logout', { method: 'POST' }) } catch { /* already signed out */ }
    setAuth(current => ({ ...current, user: null })); setMyRepos([]); setSavedJobs([]); setJob(null); setAssessment(null); setHistory([])
    say('Signed out. Your jobs stay saved to your GitHub account.')
    request('/jobs').then(setSavedJobs).catch(() => {})
  }
  async function findReadyJobs(event) {
    event.preventDefault()
    if (!assessment) return say('Assess your repository first, then find jobs you are ready for.', 'warn')
    setBusy('Ranking open postings against your repository…')
    try {
      const params = new URLSearchParams({ repository: assessment.portfolio.repository, source: ready.source, boards: ready.boards, q: ready.q.trim() })
      const results = await request(`/jobs/ready?${params}`)
      setReady(current => ({ ...current, results }))
      say(results.jobs.length ? `Ranked ${results.scanned} open postings. ${results.jobs.filter(job => job.score >= 75 && job.required.length >= 3).length} are a strong match.` : 'No postings with code-checkable requirements were found on those boards.', results.jobs.length ? 'success' : 'warn')
    } catch (error) { fail(error) } finally { setBusy('') }
  }
  async function importAndAssess(listing) {
    setBusy(`Importing "${listing.title}" and assessing your repository…`)
    try {
      const found = await request('/jobs/import', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ source: listing.source, board: listing.board, jobId: listing.id }) })
      chooseJob(found); setSavedJobs(items => [found, ...items])
      const result = await request('/assessments', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ jobId: found.id, repository: assessment.portfolio.repository, branch }) })
      setAssessment(result); setHistory(await request(historyPath(found.id, result.portfolio.repository)))
      say(`Assessed against ${found.title} at ${found.company}. Readiness ${readiness(result.gaps)}%.`, 'success')
      window.setTimeout(() => document.getElementById('results')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 450)
    } catch (error) { fail(error) } finally { setBusy('') }
  }
  async function importPosting(listing) {
    setBusy(`Reading "${listing.title}" from ${listing.company}…`)
    try {
      const found = await request('/jobs/import', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ source: listing.source, board: listing.board, jobId: listing.id }) })
      chooseJob(found); setSavedJobs(items => [found, ...items])
      say(`Imported from ${found.company}'s job board with its source link. Found ${found.requiredSkills.length} requirement${found.requiredSkills.length === 1 ? '' : 's'}.`, 'success')
    } catch (error) { fail(error) } finally { setBusy('') }
  }
  async function discoverJob(event) {
    event.preventDefault()
    const site = detectSite(source.sourceUrl)
    if (site?.needsText && !source.postingText.trim()) return say(`${site.label} doesn't allow automated reading, so copy the job description into Posting text. The link is still saved as the source.`, 'warn')
    setBusy('Reading the job posting…')
    try {
      const found = await request('/jobs/discover', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(source) })
      chooseJob(found); setSavedJobs(items => [found, ...items]); say(`Found ${found.requiredSkills.length} requirement${found.requiredSkills.length === 1 ? '' : 's'}. Review them, then assess a repository.`, 'success')
    } catch (error) { fail(error) } finally { setBusy('') }
  }
  async function saveRequirements() {
    setBusy('Saving requirements…')
    try {
      const updated = await request(`/jobs/${encodeURIComponent(job.id)}/requirements`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ skills: selectedSkills }) })
      chooseJob(updated); setSavedJobs(items => items.map(item => item.id === updated.id ? updated : item))
      say('Requirements saved. Run a new assessment to compare against the edited list.', 'success')
    } catch (error) { fail(error) } finally { setBusy('') }
  }
  async function resolveRepository() {
    if (!repository.trim()) return say('Enter a repository as owner/name.', 'warn')
    setBusy('Checking repository…')
    try {
      const resolved = await request(`/repositories/resolve?repository=${encodeURIComponent(repository.trim())}`)
      setRepoDetails(resolved); setRepository(resolved.fullName); say(`Found ${resolved.fullName}. Default branch: ${resolved.defaultBranch}.`, 'success')
    } catch (error) { setRepoDetails(null); fail(error) } finally { setBusy('') }
  }
  async function assess() {
    if (!job || !repository.trim()) return say('Choose a job and enter a repository first.', 'warn')
    if (!sameSkills(selectedSkills, job.requiredSkills)) return say('Save your requirement changes before assessing.', 'warn')
    setBusy(ai?.enabled ? 'Scanning the code and asking the AI to review the evidence…' : 'Scanning the code and comparing evidence…')
    try {
      const result = await request('/assessments', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ jobId: job.id, repository: repository.trim(), branch }) })
      const previous = assessment?.portfolio.revision || history[0]?.portfolio.revision
      const newCommit = !!previous && previous !== result.portfolio.revision
      setRepository(result.portfolio.repository); setAssessment(result); setJustUpdated(newCommit); setHistory(await request(historyPath(job.id, result.portfolio.repository)))
      say(newCommit ? `New commit ${shortSha(result.portfolio.revision)} assessed. See what changed below.` : `Assessment complete at commit ${shortSha(result.portfolio.revision)}.`, 'success')
      window.setTimeout(() => document.getElementById('results')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 450)
    } catch (error) { fail(error) } finally { setBusy('') }
  }
  async function watch() {
    if (!assessment) return say('Assess the repository once before watching it.', 'warn')
    setBusy('Starting change tracking…')
    try {
      const result = await request('/repositories/watch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ repository: repository.trim(), branch, installGitHubWebhook: installWebhook }) })
      setRepository(result.repository); setWatching(true); say(`${result.message} Push a commit to see the assessment update.`, 'success')
    } catch (error) { fail(error) } finally { setBusy('') }
  }

  const gaps = assessment?.gaps || []
  const counts = { DEMONSTRATED: 0, PARTIAL: 0, NOT_DEMONSTRATED: 0 }
  gaps.forEach(gap => { counts[gap.status in counts ? gap.status : 'NOT_DEMONSTRATED']++ })
  const earlier = history.find(item => item.portfolio.revision !== assessment?.portfolio.revision)
  const isLatest = assessment && assessment.portfolio.revision === history[0]?.portfolio.revision
  const changes = {}
  if (earlier && isLatest) gaps.forEach(gap => {
    const before = earlier.gaps.find(old => old.skill === gap.skill)?.status
    if (before && before !== gap.status) changes[gap.skill] = before === 'DEMONSTRATED' ? 'Changed' : 'Improved'
  })
  const improved = gaps.filter(gap => changes[gap.skill] === 'Improved')
  const change = isLatest ? assessment?.change : null
  const changedPaths = new Set((change?.files || []).map(file => file.path))
  const recheckOf = skill => !change ? null : change.reassessedSkills.includes(skill) ? 'rechecked' : 'unchanged'
  const visible = gaps.filter(gap => filter === 'ALL' || (filter === 'GAPS' ? gap.status !== 'DEMONSTRATED' : gap.status === 'DEMONSTRATED'))
  const coverage = assessment?.portfolio
  // Each step is done when its evidence exists; the current step is the first one not done yet.
  const codeChanged = !!earlier && !!isLatest
  const reassessing = !!pending && pending !== assessment?.portfolio.revision
  const doneSteps = [!!job, !!assessment, !!assessment, codeChanged || reassessing, codeChanged && !reassessing]
  const firstOpen = doneSteps.indexOf(false)
  // When the loop has just completed, keep the highlight on "Updated assessment" so the finish is visible.
  const stage = firstOpen !== -1 ? firstOpen : justUpdated ? LOOP.length - 1 : LOOP.length
  const stepTargets = ['role-card', 'repo-card', 'results', codeChanged ? 'results' : 'close-loop', 'results']
  const goTo = index => document.getElementById(stepTargets[index])?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  const firstGap = gaps.find(gap => gap.status !== 'DEMONSTRATED')
  const site = detectSite(source.sourceUrl)
  const quotes = Object.fromEntries((job?.requirementQuotes || []).map(item => [item.skill, item]))

  const assessing = busy.startsWith('Scanning')
  const headline = ['Show', 'what', 'your', 'code']

  if (!authChecked) return <div className="loginScreen" aria-busy="true" />
  if (!auth.user && !guest) return <div className="loginScreen">
    <div className="loginCard">
      <span className="logo"><Icon name="logo" size={24} /></span>
      <h1>ProofPath</h1>
      <p className="loginLead">Show what your code actually proves. Sign in to keep your jobs and history on every device.</p>
      {auth.configured && <a className="githubButton big" href={`${API}/auth/github/login`}><Icon name="github" size={18} />Continue with GitHub</a>}
      {auth.configured && <p className="divider"><span>or use an email</span></p>}
      <div className="authTabs" role="tablist">
        <button type="button" role="tab" aria-selected={form.mode === 'signin'} className={form.mode === 'signin' ? 'on' : ''} onClick={() => setForm({ ...form, mode: 'signin', error: '', notice: '' })}>Sign in</button>
        <button type="button" role="tab" aria-selected={form.mode === 'signup'} className={form.mode === 'signup' ? 'on' : ''} onClick={() => setForm({ ...form, mode: 'signup', error: '', notice: '' })}>Create account</button>
      </div>
      <form className="loginForm" onSubmit={submitAccount}>
        {form.mode === 'signup' && <label>Name<input value={form.name} onChange={event => setForm({ ...form, name: event.target.value })} placeholder="Your name" autoComplete="name" maxLength={80} /></label>}
        <label>Email<input type="email" required value={form.email} onChange={event => setForm({ ...form, email: event.target.value })} placeholder="you@example.com" autoComplete="email" /></label>
        {form.mode !== 'forgot' && <label>Password<input type="password" required minLength={form.mode === 'signup' ? 8 : undefined} value={form.password} onChange={event => setForm({ ...form, password: event.target.value })} placeholder={form.mode === 'signup' ? 'At least 8 characters' : 'Your password'} autoComplete={form.mode === 'signup' ? 'new-password' : 'current-password'} maxLength={128} /></label>}
        {form.error && <p className="formError" role="alert">{form.error}</p>}
        {form.notice && <p className="formNotice" role="status">{form.notice}</p>}
        <button className="primary" disabled={form.busy}>{form.busy ? 'Please wait…' : form.mode === 'signup' ? 'Create account' : form.mode === 'forgot' ? 'Send reset link' : 'Sign in'}</button>
        {form.mode === 'signin' && auth.passwordReset && <button type="button" className="ghost forgotLink" onClick={() => setForm({ ...form, mode: 'forgot', error: '', notice: '' })}>Forgot your password?</button>}
        {form.mode === 'forgot' && <button type="button" className="ghost forgotLink" onClick={() => setForm({ ...form, mode: 'signin', error: '', notice: '' })}>Back to sign in</button>}
      </form>
      <button type="button" className="ghost" onClick={continueAsGuest}>Continue without signing in</button>
      <p className="muted small">Email accounts can assess any public repository. Signing in with GitHub also lets you pick your own repositories, include private ones, and open issues.</p>
    </div>
  </div>

  return <LazyMotion features={loadFeatures} strict><MotionConfig reducedMotion="user">
  <div className="app">
    <m.nav className="topbar" initial={{ y: -24, opacity: 0 }} animate={{ y: 0, opacity: 1 }} transition={{ duration: 0.5, ease }}>
      <div className="brand"><m.span className="logo" whileHover={{ rotate: -8, scale: 1.08 }} transition={spring}><Icon name="logo" size={20} /></m.span><span>ProofPath</span><span className="tag">Kurukshetra Hackathon</span></div>
      <div className="pills">
        <AnimatePresence>{watching && <m.span key="live" className="pill live" initial={{ opacity: 0, scale: 0.8 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.8 }} transition={spring}><i className="dot" />Watching {repository}</m.span>}</AnimatePresence>
        {auth.user
          ? <span className="account">{auth.user.avatarUrl && <img src={auth.user.avatarUrl} alt="" width="26" height="26" />}<span>{auth.user.provider === 'email' ? auth.user.name : auth.user.login}</span><button type="button" className="ghost" onClick={signOut}>Sign out</button></span>
          : auth.configured && <a className="githubButton" href={`${API}/auth/github/login`}><Icon name="github" size={16} />Sign in with GitHub</a>}
      </div>
    </m.nav>
    {auth.user && auth.emailPending && <div className="verifyBanner" role="status">
      <span>Confirm your email: we sent a link to {auth.user.login.replace('email:', '')}.</span>
      <button type="button" className="ghost" disabled={resent} onClick={() => { request('/auth/resend-verification', { method: 'POST' }).then(() => setResent(true)).catch(error => say(error.message, 'error')) }}>{resent ? 'Sent' : 'Resend link'}</button>
    </div>}

    <m.header className="hero" initial={false} animate="show">
      <m.p className="eyebrow" variants={fadeUp}><Icon name="spark" size={14} /> AI Career Coach</m.p>
      <h1>
        {headline.map(word => <m.span key={word} className="word" variants={fadeUp}>{word} </m.span>)}
        <m.span className="gradient shimmer" variants={{ hidden: { opacity: 0, y: 24, scale: 0.96 }, show: { opacity: 1, y: 0, scale: 1, transition: { duration: 0.7, ease } } }}>actually proves.</m.span>
      </h1>
      <m.p className="lead" variants={fadeUp}>ProofPath reads a real job posting, reviews the evidence in your GitHub repository, explains every gap with cited files, and reassesses automatically each time you push.</m.p>
      <m.ol className="loop" aria-label="Assessment loop" variants={stagger(0.06)}>{LOOP.map((step, index) =>
        <m.li key={step} variants={fadeUp} className={[doneSteps[index] ? 'done' : '', index === stage ? 'current' : ''].join(' ').trim()}>
          <button type="button" onClick={() => goTo(index)} disabled={index > stage} title={index > stage ? 'Finish the earlier steps first' : `Go to: ${step}`}>
            {index === stage && <m.i className="loopActive" layoutId="loopActive" transition={spring} />}
            <span className="loopNo">{doneSteps[index] ? <Icon name="check" size={14} /> : index + 1}</span>{step}
            {index === 3 && !doneSteps[3] && index === stage && <em className="loopHint">{watching ? <><i className="dot" />waiting for a push</> : 'push a commit'}</em>}
            {index === 3 && reassessing && <em className="loopHint"><code>{shortSha(pending)}</code> detected</em>}
            {index === 4 && reassessing && <em className="loopHint"><i className="spinner tiny" />reassessing…</em>}
            {index === 4 && !reassessing && doneSteps[4] && justUpdated && <em className="loopHint">new results below</em>}
          </button>
        </m.li>)}
      </m.ol>
    </m.header>

    <div className={`status status-${busy ? 'busy' : message.tone}`} role="status" aria-live="polite">
      {busy ? <i className="spinner" /> : <Icon name={message.tone === 'error' ? 'minus' : message.tone === 'success' ? 'check' : 'spark'} size={16} />}
      <m.span key={busy || message.text} initial={{ opacity: 0.4, y: 4 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.25 }}>{busy || message.text}</m.span>
      {busy && <i className="progress" />}
    </div>

    <m.section className="setup" initial={false} animate="show">
      <m.article id="role-card" className="card glow" variants={cardIn} onPointerMove={spotlight}>
        <div className="cardHead"><span className="stepNo">1</span><div><h2>Target role</h2><p>Paste a real job or internship posting.</p></div><Icon name="briefcase" size={22} /></div>
        {savedJobs.length > 0 && <label>Saved jobs<select value={savedJobs.some(item => item.id === job?.id) ? job.id : ''} onChange={event => { const next = savedJobs.find(item => item.id === event.target.value); if (next && next.id !== job?.id) chooseJob(next) }}><option value="">Select a saved job</option>{savedJobs.map(item => <option key={item.id} value={item.id}>{item.title} · {item.company}</option>)}</select></label>}
        <form onSubmit={discoverJob}>
          <div className="row"><label>Role<input required value={source.title} onChange={event => setSource({ ...source, title: event.target.value })} placeholder="Backend Engineer Intern" /></label><label>Company<input required value={source.company} onChange={event => setSource({ ...source, company: event.target.value })} placeholder="Company name" /></label></div>
          <label>Job posting URL<input required type="url" value={source.sourceUrl} onChange={event => setSource({ ...source, sourceUrl: event.target.value })} placeholder="https://careers.example.com/job" /></label>
          <label>Posting text{site?.needsText ? '' : ' (optional)'}<textarea required={!!site?.needsText} value={source.postingText} onChange={event => setSource({ ...source, postingText: event.target.value })} placeholder={site?.needsText ? `Copy the full job description from ${site.label} and paste it here.` : 'If the website blocks fetching, paste the job description here.'} rows="4" /></label>
          {site && <p className="muted small hintLine">{site.needsText ? <>{site.label} link: {site.label} blocks automated reading, so paste the description. The <strong>link is kept</strong> as the source.</> : <>{site.label} job link: read through {site.label}'s official job-board API.</>}</p>}
          <div className="buttons"><m.button className="primary" disabled={!!busy} whileHover={{ y: -2 }} whileTap={{ scale: 0.96 }} transition={spring}>Read job posting</m.button></div>
        </form>
        <details className="advanced boardSearch"><summary>Or search a public job board (Greenhouse, Lever, Ashby)</summary>
          <form onSubmit={searchBoard}>
            <div className="row"><label>Job board<select value={boardQuery.source} onChange={event => { setBoardQuery({ ...boardQuery, source: event.target.value }); setBoardResults(null) }}>{BOARDS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
              <label>Company board name<input required value={boardQuery.board} onChange={event => setBoardQuery({ ...boardQuery, board: event.target.value })} placeholder={`e.g. ${(BOARDS.find(item => item[0] === boardQuery.source) || BOARDS[0])[2]}`} /></label></div>
            <label>Keyword (optional)<input value={boardQuery.q} onChange={event => setBoardQuery({ ...boardQuery, q: event.target.value })} placeholder="engineer, intern…" /></label>
            <div className="buttons"><button className="secondary" disabled={!!busy}>Search postings</button></div>
          </form>
          {boardResults?.jobs?.length > 0 && <m.ul className="boardResults" variants={stagger(0.03)} initial="hidden" animate="show">{boardResults.jobs.map(listing =>
            <m.li key={listing.id} variants={fadeUp}><div><strong>{listing.title}</strong><span>{listing.company}{listing.location ? ` · ${listing.location}` : ''}</span></div>
              <a href={listing.url} target="_blank" rel="noreferrer" title="Open the original posting"><Icon name="link" size={14} /></a>
              <button type="button" className="secondary" disabled={!!busy} onClick={() => importPosting(listing)}>Import</button></m.li>)}</m.ul>}
        </details>
        <AnimatePresence mode="wait">{job && <m.div key={job.id} className="jobBox" initial={{ opacity: 0, y: 14, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: -10 }} transition={{ duration: 0.35, ease }}>
          <div className="jobTitle"><div><strong>{job.title}</strong><span>{job.company}</span></div><a href={job.sourceUrl} target="_blank" rel="noreferrer">Source <Icon name="link" size={14} /></a></div>
          <p className="muted">{byAi(job.extractedBy) ? <>Requirements read by AI ({aiName(job.extractedBy)}). Each one quotes the posting.</> : 'Requirements matched by keyword rules.'} Click a skill to include or exclude it.</p>
          <m.div className="skillToggles" variants={stagger(0.035, 0.1)} initial="hidden" animate="show">{SKILLS.map(skill => { const on = selectedSkills.includes(skill); return <m.button layout type="button" key={skill} variants={{ hidden: { opacity: 0, scale: 0.8 }, show: { opacity: 1, scale: 1 } }} whileTap={{ scale: 0.92 }} transition={spring} aria-pressed={on} className={`toggle ${on ? 'on' : ''}`} onClick={() => setSelectedSkills(current => on ? current.filter(item => item !== skill) : [...current, skill])}>{on && <Icon name="check" size={13} />}{pretty(skill)}{quotes[skill]?.importance === 'PREFERRED' && <em>preferred</em>}</m.button> })}</m.div>
          {job.requirementQuotes?.length > 0 && <m.ul className="quotes" variants={stagger(0.06, 0.25)} initial="hidden" animate="show">{job.requirementQuotes.map(item => <m.li key={item.skill} variants={{ hidden: { opacity: 0, x: -12 }, show: { opacity: 1, x: 0 } }}><Icon name="quote" size={14} /><span><strong>{pretty(item.skill)}</strong> “{item.quote}”</span></m.li>)}</m.ul>}
          <AnimatePresence>{!sameSkills(selectedSkills, job.requiredSkills) && <m.button key="save" type="button" className="secondary" disabled={!!busy || selectedSkills.length === 0} onClick={saveRequirements} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 8 }}>Save requirements</m.button>}</AnimatePresence>
        </m.div>}</AnimatePresence>
      </m.article>

      <m.article id="repo-card" className="card glow" variants={cardIn} onPointerMove={spotlight}>
        <div className="cardHead"><span className="stepNo">2</span><div><h2>Your repository</h2><p>Any public GitHub repository, scanned at an exact commit.</p></div><Icon name="repo" size={22} /></div>
        {auth.user && myRepos.length > 0 && <label>Your repositories<select value={myRepos.some(repo => repo.fullName.toLowerCase() === repository.trim().toLowerCase()) ? myRepos.find(repo => repo.fullName.toLowerCase() === repository.trim().toLowerCase()).fullName : ''} onChange={event => { const picked = myRepos.find(repo => repo.fullName === event.target.value); if (!picked) return; setRepository(picked.fullName); setBranch(''); setWatching(false); setRepoDetails({ fullName: picked.fullName, defaultBranch: picked.defaultBranch, htmlUrl: `https://github.com/${picked.fullName}`, privateRepository: picked.privateRepository }) }}>
          <option value="">Choose one of your {myRepos.length} repositories</option>
          {myRepos.map(repo => <option key={repo.fullName} value={repo.fullName}>{repo.fullName}{repo.privateRepository ? ' (private)' : ''}{repo.language ? ` · ${repo.language}` : ''}</option>)}
        </select></label>}
        {auth.user && auth.user.provider !== 'email' && !auth.user.privateAccess && <p className="muted small hintLine">Showing public repositories. <a href={`${API}/auth/github/login?privateRepos=true`}>Include private repositories</a></p>}
        {!auth.user && auth.configured && <p className="muted small hintLine"><a href={`${API}/auth/github/login`}>Sign in with GitHub</a> to pick from your repositories, include private ones, and keep your jobs on every device.</p>}
        <label>{auth.user ? 'Or type any public repository' : 'Repository'}<div className="inputGroup"><span>github.com/</span><input value={repository} onChange={event => { setRepository(event.target.value); setRepoDetails(null); setWatching(false) }} placeholder="owner/repository" /></div></label>
        <label>Branch<input value={branch} onChange={event => { setBranch(event.target.value); setWatching(false) }} placeholder="Default branch" /></label>
        <div className="buttons"><button className="secondary" type="button" disabled={!!busy} onClick={resolveRepository}>Check repository</button></div>
        <AnimatePresence>{repoDetails && <m.div key={repoDetails.fullName} className="found" initial={{ opacity: 0, height: 0, marginTop: 0 }} animate={{ opacity: 1, height: 'auto', marginTop: 14 }} exit={{ opacity: 0, height: 0, marginTop: 0 }}><Icon name="check" size={16} /><span>Found <a href={repoDetails.htmlUrl} target="_blank" rel="noreferrer">{repoDetails.fullName}</a> · default branch <code>{repoDetails.defaultBranch}</code></span></m.div>}</AnimatePresence>
        <div className="cta">
          <m.button className="primary big shine" type="button" onClick={assess} disabled={!!busy || !job || !repository.trim()} whileHover={{ y: -2 }} whileTap={{ scale: 0.96 }} transition={spring}>{assessment ? 'Rescan now' : 'Assess repository'}<Icon name="arrow" size={18} /></m.button>
          <m.button className={`secondary big ${watching ? 'isLive' : ''}`} type="button" onClick={watch} disabled={!!busy || !assessment} whileHover={{ y: -2 }} whileTap={{ scale: 0.96 }} transition={spring}>{watching ? <><i className="dot" />Watching changes</> : <><Icon name="eye" size={18} />Watch changes</>}</m.button>
        </div>
        {watching && <p className="muted small">Polling GitHub every minute{installWebhook ? ' and listening for push webhooks' : ''}. This page checks for updates every few seconds.</p>}
        <details className="advanced"><summary>Advanced: GitHub push webhook</summary><label className="checkRow"><input type="checkbox" checked={installWebhook} onChange={event => setInstallWebhook(event.target.checked)} />Install a GitHub webhook</label></details>
      </m.article>
    </m.section>

    {assessing && !assessment && <SkeletonResults />}
      {assessment && <m.section key="results" id="results" className="results" variants={stagger(0.1)} initial="hidden" animate="show">
        {(reassessing || (justUpdated && isLatest)) && <m.div className={`banner ${reassessing ? 'pendingBanner' : ''}`} initial={{ opacity: 0, y: -16, scale: 0.96 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={spring}>
          {reassessing
            ? <><i className="spinner" /><div><strong>Commit {shortSha(pending)} detected. Reassessing…</strong><span>The AI is reviewing the changed code. The results below update when it finishes.</span></div></>
            : <>
              <m.span key={`icon-${assessment.portfolio.revision}`} className="bannerIcon" initial={{ scale: 0.4 }} animate={{ scale: 1, rotate: [0, -12, 12, -6, 0] }} transition={{ duration: 0.8 }}><Icon name="bolt" size={20} /></m.span>
              <div><strong>Reassessed automatically after commit {shortSha(assessment.portfolio.revision)}</strong><span>{change ? `${change.files.length} file${change.files.length === 1 ? '' : 's'} changed; ${change.reassessedSkills.length ? `re-checked ${change.reassessedSkills.map(pretty).join(', ')}` : 'no skill evidence was touched'}. ` : ''}{improved.length ? `${improved.map(gap => pretty(gap.skill)).join(', ')} improved and now cite${improved.length === 1 ? 's' : ''} the changed files.` : 'No requirement changed status in this commit.'}</span></div>
            </>}
        </m.div>}

        <m.div className="overview card glow" variants={cardIn} onPointerMove={spotlight}>
          <ScoreRing value={readiness(gaps)} />
          <div className="overviewBody">
            <p className="eyebrow">Step 3 · Assessment</p>
            <h2>{assessment.job.title} <span className="muted">at {assessment.job.company}</span></h2>
            <m.div className="stats" variants={stagger(0.08, 0.2)} initial="hidden" animate="show">
              {[['good', counts.DEMONSTRATED, 'Demonstrated'], ['warn', counts.PARTIAL, 'Partial'], ['gap', counts.NOT_DEMONSTRATED, 'Not shown yet']].map(([tone, value, label]) =>
                <m.div key={tone} className={`stat ${tone}`} variants={fadeUp} whileHover={{ y: -3 }}><strong><CountUp value={value} duration={0.8} /></strong><span>{label}</span></m.div>)}
            </m.div>
            <div className="meta">
              <a className="metaItem" href={`https://github.com/${coverage.repository}/tree/${coverage.revision}`} target="_blank" rel="noreferrer"><Icon name="commit" size={15} />{coverage.repository} @ <code>{shortSha(coverage.revision)}</code></a>
              <span className="metaItem"><Icon name="file" size={15} />{coverage.scannedFiles} files read{coverage.skippedFiles > 0 ? `, ${coverage.skippedFiles} skipped` : ''}{coverage.treeTruncated ? ', partial tree' : ''}</span>
              <span className="metaItem"><Icon name="spark" size={15} />{byAi(assessment.assessedBy) ? `AI reviewed · ${aiName(assessment.assessedBy)}` : 'Keyword rules'}</span>
            </div>
            <button type="button" className="secondary reportButton" onClick={() => download(`proofpath-evidence-${assessment.portfolio.repository.replace('/', '-')}-${shortSha(assessment.portfolio.revision)}.md`, evidenceReport(assessment, history))}><Icon name="file" size={15} />Download evidence report</button>
          </div>
        </m.div>

        {change && <m.div className="changePanel card" variants={cardIn}>
          <div className="changeHead"><span className="summaryIcon"><Icon name="commit" size={18} /></span><div><h3>What changed in this commit</h3>
            <p className="muted small"><a href={`https://github.com/${coverage.repository}/compare/${change.baseRevision}...${change.headRevision}`} target="_blank" rel="noreferrer"><code>{shortSha(change.baseRevision)}</code> → <code>{shortSha(change.headRevision)}</code></a> · only skills whose files changed were reassessed</p></div></div>
          <div className="changeGrid">
            <div><p className="label">Changed files ({change.files.length})</p><ul className="changedFiles">{change.files.slice(0, 12).map(file => <li key={file.path}><span className={`fileStatus st-${file.status}`}>{file.status}</span><a href={file.url} target="_blank" rel="noreferrer">{file.path}</a></li>)}{change.files.length > 12 && <li className="muted small">and {change.files.length - 12} more</li>}{change.files.length === 0 && <li className="muted small">GitHub listed no file changes.</li>}</ul></div>
            <div><p className="label">Re-checked</p><div className="skillChips">{change.reassessedSkills.length ? change.reassessedSkills.map(skill => <span key={skill} className="chip chip-rechecked">{pretty(skill)}</span>) : <span className="muted small">None: no evidence files changed</span>}</div>
              <p className="label">Unchanged, carried over</p><div className="skillChips">{change.carriedOverSkills.length ? change.carriedOverSkills.map(skill => <span key={skill} className="chip chip-unchanged">{pretty(skill)}</span>) : <span className="muted small">None</span>}</div>
              {!change.complete && <p className="muted small">GitHub could not list every change, so every skill was reassessed.</p>}</div>
          </div>
        </m.div>}

        {assessment.summary && byAi(assessment.assessedBy) && <m.div className="summary card" variants={cardIn}><m.span className="summaryIcon" animate={{ rotate: [0, 14, -10, 0], scale: [1, 1.12, 1] }} transition={{ duration: 2.4, repeat: Infinity, repeatDelay: 3 }}><Icon name="spark" size={18} /></m.span><div><h3>Coach summary</h3><p>{assessment.summary}</p></div></m.div>}

        {!codeChanged && isLatest && <m.div id="close-loop" className="closeLoop card glow" variants={cardIn} onPointerMove={spotlight}>
          <div className="closeHead"><span className="stepNo">4</span><div><h3>Close the loop: prove a gap with a real commit</h3><p className="muted small">This is the change-detection step. ProofPath notices the new commit and reassesses from the code alone, citing the changed files.</p></div></div>
          <ol className="closeSteps">
            <li className={watching ? 'ok' : ''}><span className="loopNo">{watching ? <Icon name="check" size={14} /> : 1}</span><div><strong>Turn on change tracking</strong>{watching ? <p className="muted small">Watching {repository}. GitHub is checked every minute.</p> : <m.button type="button" className="primary" onClick={watch} disabled={!!busy} whileHover={{ y: -2 }} whileTap={{ scale: 0.96 }} transition={spring}><Icon name="eye" size={16} />Watch changes</m.button>}</div></li>
            <li><span className="loopNo">2</span><div><strong>Push a commit that fills a gap{firstGap ? ` such as ${pretty(firstGap.skill)}` : ''}</strong><p className="muted small">{firstGap ? (firstGap.nextStep || NEXT[firstGap.skill]) : 'Every requirement is demonstrated. Push any commit to see the reassessment.'}</p>{firstGap?.skill === 'SQL' && <code className="sample">{"queries/applications.sql\nSELECT id, company, status FROM applications WHERE status = 'APPLIED';"}</code>}</div></li>
            <li><span className="loopNo">3</span><div><strong>Watch the assessment update</strong><p className="muted small">It appears here within about a minute, with an "Improved" tag on the skill. In a hurry? <button type="button" className="linkInline" onClick={assess} disabled={!!busy}>Rescan now</button>.</p></div></li>
          </ol>
        </m.div>}

        <m.div className="resultsHead" variants={fadeUp}>
          <h2>Skill evidence</h2>
          <div className="tabs" role="tablist">{[['ALL', `All ${gaps.length}`], ['GAPS', `Gaps ${counts.PARTIAL + counts.NOT_DEMONSTRATED}`], ['PROVED', `Proved ${counts.DEMONSTRATED}`]].map(([key, label]) =>
            <button key={key} role="tab" aria-selected={filter === key} className={filter === key ? 'active' : ''} onClick={() => setFilter(key)}>{filter === key && <m.i className="tabPill" layoutId="tabPill" transition={spring} />}<span>{label}</span></button>)}</div>
        </m.div>
        <m.p className="muted small disclaimer" variants={fadeUp}>A missing signal means only that this scan did not find evidence, not that you lack the skill. Every positive verdict cites a file at the scanned commit.</m.p>
        <m.div className="skills" layout variants={stagger(0.07)}>
          <AnimatePresence mode="popLayout">{visible.map(gap => <SkillCard key={gap.skill} gap={gap} change={changes[gap.skill]} recheck={recheckOf(gap.skill)} changedPaths={changedPaths} learning={paths[gap.skill]} fix={fixFor(gap)} />)}</AnimatePresence>
        </m.div>
        {visible.length === 0 && <p className="muted empty">Nothing in this view.</p>}

        {job && <PortfolioPanel key={`${job.id}|${assessment.portfolio.repository}`} job={job} repository={assessment.portfolio.repository} branch={branch} who={auth.user?.login || 'guest'} suggestions={myRepos.map(repo => repo.fullName)} say={say} fail={fail} />}

        <GrowthPanel repository={assessment.portfolio.repository} auth={auth} say={say} fail={fail} />

        <m.div className="readyJobs card" variants={cardIn} id="ready-jobs">
          <div className="changeHead"><span className="summaryIcon"><Icon name="target" size={18} /></span><div><h3>Jobs you're ready for</h3>
            <p className="muted small">Ranks open postings on company job boards by how many of their requirements {assessment.portfolio.repository} already proves.</p></div></div>
          <form onSubmit={findReadyJobs} className="readyForm">
            <label>Job board<select value={ready.source} onChange={event => setReady({ ...ready, source: event.target.value, results: null })}>{BOARDS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
            <label>Companies<input required value={ready.boards} onChange={event => setReady({ ...ready, boards: event.target.value })} placeholder={`e.g. ${(BOARDS.find(item => item[0] === ready.source) || BOARDS[0])[2]}, airbnb`} /></label>
            <label>Keyword<input value={ready.q} onChange={event => setReady({ ...ready, q: event.target.value })} placeholder="intern, backend…" /></label>
            <button className="primary" disabled={!!busy}>Find matches</button>
          </form>
          {ready.results?.warnings?.map(warning => <p key={warning} className="muted small warnLine">{warning}</p>)}
          {ready.results?.jobs?.length > 0 && <m.ol className="readyList" variants={stagger(0.04)} initial="hidden" animate="show">{ready.results.jobs.map(item =>
            <m.li key={`${item.listing.board}-${item.listing.id}`} variants={fadeUp}>
              <div className={`readyScore ${item.score >= 75 ? 'high' : item.score >= 50 ? 'mid' : 'low'}`}><strong>{item.score}%</strong><span>{item.met.length}/{item.required.length}</span></div>
              <div className="readyBody">
                <strong>{item.listing.title}</strong>
                <span className="muted small">{item.listing.company}{item.listing.location ? ` · ${item.listing.location}` : ''}</span>
                <p className="readyLine">{item.missing.length === 0 && item.partial.length === 0 ? `You meet all ${item.required.length} code-checkable requirements${item.required.length < 3 ? ' (few are listed, so the score is cautious)' : ''}.` : `You meet ${item.met.length} of ${item.required.length}${item.missing.length ? `. Missing: ${item.missing.map(pretty).join(', ')}` : ''}${item.partial.length ? `. Partly shown: ${item.partial.map(pretty).join(', ')}` : ''}.`}</p>
                <div className="skillChips">{item.met.map(skill => <span key={skill} className="chip chip-met">{pretty(skill)}</span>)}{item.partial.map(skill => <span key={skill} className="chip chip-partial">{pretty(skill)}</span>)}{item.missing.map(skill => <span key={skill} className="chip chip-missing">{pretty(skill)}</span>)}</div>
              </div>
              <div className="readyActions"><a href={item.listing.url} target="_blank" rel="noreferrer" className="ghost">Posting <Icon name="link" size={13} /></a><button type="button" className="secondary" disabled={!!busy} onClick={() => importAndAssess(item.listing)}>Assess me</button></div>
            </m.li>)}</m.ol>}
          {ready.results && <p className="muted small">Quick match from keyword rules across {ready.results.scanned} postings. “Assess me” imports the posting and runs the full AI review with cited evidence.</p>}
        </m.div>

        <ActivityFeed history={history} />

        {history.length > 1 && <m.div className="timeline card" variants={cardIn}>
          <h3>Scan history</h3><p className="muted small">Each push creates a new scan. Select one to compare.</p>
          <m.ol variants={stagger(0.05)}>{history.map((item, index) => { const active = assessment.portfolio.scannedAt === item.portfolio.scannedAt; return <m.li key={`${item.portfolio.scannedAt}-${index}`} variants={fadeUp}><m.button type="button" className={active ? 'active' : ''} whileHover={{ y: -3 }} whileTap={{ scale: 0.97 }} onClick={() => { setAssessment(item); setJustUpdated(false) }}><span className="tlScore">{readiness(item.gaps)}%</span><span className="tlSha"><code>{shortSha(item.portfolio.revision)}</code>{index === 0 && <em>latest</em>}</span><span className="tlDate">{new Date(item.portfolio.scannedAt).toLocaleString()}</span></m.button></m.li> })}</m.ol>
        </m.div>}
      </m.section>}

    <footer className="footer"><span>ProofPath · built for the <strong>Kurukshetra Hackathon</strong> · AI Career Coach challenge</span><span>Evidence is a signal from code, not a full evaluation of a person.</span></footer>
  </div>
  </MotionConfig></LazyMotion>
}
