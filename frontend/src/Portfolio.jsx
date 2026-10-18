import { useState } from 'react'
import { postJson, request } from './api.js'

const pretty = value => value.replaceAll('_', ' ')
const shortSha = sha => (sha || '').slice(0, 7)
const LABEL = { DEMONSTRATED: 'Demonstrated', PARTIAL: 'Partial', NOT_DEMONSTRATED: 'Gap' }
const CHIP = { DEMONSTRATED: 'chip-met', PARTIAL: 'chip-partial', NOT_DEMONSTRATED: 'chip-missing' }
const REPO = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/

function useStored(key, initial) {
  const [value, setValue] = useState(() => { try { return JSON.parse(localStorage.getItem(key)) ?? initial } catch { return initial } })
  return [value, next => { setValue(next); try { localStorage.setItem(key, JSON.stringify(next)) } catch { /* storage unavailable */ } }]
}

function ago(iso) {
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000)
  if (seconds < 90) return 'just now'
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`
  if (seconds < 86400) return `${Math.round(seconds / 3600)} h ago`
  return `${Math.round(seconds / 86400)} d ago`
}

/** Skill verdict changes between consecutive scans (history is newest first): the loop made visible. */
export function skillChanges(history) {
  const events = []
  for (let index = 0; index < history.length - 1; index++) {
    const newer = history[index], older = history[index + 1]
    for (const gap of newer.gaps) {
      const before = older.gaps.find(item => item.skill === gap.skill)
      if (before && before.status !== gap.status)
        events.push({ at: newer.portfolio.scannedAt, revision: newer.portfolio.revision, skill: gap.skill, from: before.status, to: gap.status, files: newer.change?.files?.length ?? null })
    }
  }
  return events
}

export function ActivityFeed({ history }) {
  const events = skillChanges(history).slice(0, 8)
  if (!events.length) return null
  return <section className="card activity">
    <h3>What changed</h3>
    <p className="muted small">Every time a push moves a skill, it shows up here.</p>
    <ol className="activityList">{events.map((event, index) => <li key={`${event.revision}-${event.skill}-${index}`}>
      <span className="activityDot" data-up={event.to === 'DEMONSTRATED' || (event.to === 'PARTIAL' && event.from === 'NOT_DEMONSTRATED')} />
      <div>
        <strong>{pretty(event.skill)}</strong>
        <span className={`chip ${CHIP[event.from]}`}>{LABEL[event.from]}</span>→<span className={`chip ${CHIP[event.to]}`}>{LABEL[event.to]}</span>
        <span className="muted small">commit {shortSha(event.revision)}{event.files != null ? ` · ${event.files} file${event.files === 1 ? '' : 's'} changed` : ''} · {ago(event.at)}</span>
      </div>
    </li>)}</ol>
  </section>
}

/** One job judged against up to five repositories: the best evidence per requirement wins. */
export function PortfolioPanel({ job, repository, branch, who, suggestions, say, fail }) {
  const [extras, setExtras] = useStored(`pp_portfolio:${who}:${job.id}`, [])
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState('')
  const [combined, setCombined] = useState(null)
  const others = extras.filter(name => name.toLowerCase() !== repository.toLowerCase())

  function add(event) {
    event.preventDefault()
    const name = draft.trim().replace(/^https?:\/\/github\.com\//i, '').replace(/\.git$/i, '').replace(/\/$/, '')
    if (!REPO.test(name)) return say('Enter a repository as owner/name, for example deven065/api.', 'warn')
    if (name.toLowerCase() === repository.toLowerCase() || extras.some(item => item.toLowerCase() === name.toLowerCase())) return say('That repository is already in the portfolio.', 'warn')
    if (extras.length >= 4) return say('A portfolio can combine up to five repositories.', 'warn')
    setExtras([...extras, name]); setDraft(''); setCombined(null)
  }
  const remove = name => { setExtras(extras.filter(item => item !== name)); setCombined(null) }

  async function build() {
    setBusy('Assessing each repository…')
    try {
      for (const name of others) {
        setBusy(`Assessing ${name}…`)
        await postJson('/assessments', { jobId: job.id, repository: name })  // each repository is its own request
      }
      setBusy('Combining the evidence…')
      const list = [repository, ...others].map(encodeURIComponent).join(',')
      setCombined(await request(`/assessments/combined?jobId=${encodeURIComponent(job.id)}&repositories=${list}`))
      say(`Portfolio of ${others.length + 1} repositories assessed.`, 'success')
    } catch (error) { fail(error) } finally { setBusy('') }
  }

  return <section className="card portfolio" id="portfolio">
    <h3>Portfolio: combine several repositories</h3>
    <p className="muted small">Your proof rarely lives in one repo. Add the others and each requirement takes the best evidence from any of them, cited with the repository it came from.</p>
    <ul className="repoChips"><li className="chip chip-met">{repository}</li>{others.map(name => <li key={name} className="chip">{name}<button type="button" aria-label={`Remove ${name}`} onClick={() => remove(name)}>×</button></li>)}</ul>
    <form onSubmit={add} className="portfolioAdd">
      <input list="portfolio-suggestions" value={draft} onChange={event => setDraft(event.target.value)} placeholder="owner/another-repository" aria-label="Another repository" />
      <datalist id="portfolio-suggestions">{(suggestions || []).filter(name => name.toLowerCase() !== repository.toLowerCase()).map(name => <option key={name} value={name} />)}</datalist>
      <button type="submit" className="secondary" disabled={!!busy}>Add</button>
      <button type="button" className="primary" disabled={!!busy || others.length === 0} onClick={build}>{busy || 'Assess portfolio'}</button>
    </form>
    {combined && <div className="combined">
      <div className="combinedScore"><strong>{combined.readiness}%</strong><span>combined readiness</span></div>
      <ul className="repoScores">{combined.perRepository.map(item => <li key={item.repository}><span>{item.repository}</span><b>{item.readiness}% alone</b></li>)}</ul>
      <ul className="combinedSkills">{combined.gaps.map(gap => <li key={gap.skill}>
        <div><strong>{pretty(gap.skill)}</strong><span className={`chip ${CHIP[gap.status]}`}>{LABEL[gap.status]}</span></div>
        <p className="muted small">{gap.explanation}</p>
        {gap.evidence.slice(0, 3).map(file => <a key={file.url} href={file.url} target="_blank" rel="noreferrer">{file.path}</a>)}
      </li>)}</ul>
    </div>}
  </section>
}
