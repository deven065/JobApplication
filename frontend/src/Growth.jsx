import { useEffect, useState } from 'react'
import { API, postJson, request } from './api.js'

const pretty = value => value.replaceAll('_', ' ')
const escapeHtml = value => String(value ?? '').replace(/[&<>"]/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[ch]))
const shortSha = sha => (sha || '').slice(0, 7)

function useStored(key, initial) {
  const [value, setValue] = useState(() => { try { return JSON.parse(localStorage.getItem(key)) ?? initial } catch { return initial } })
  const save = next => { setValue(next); try { localStorage.setItem(key, JSON.stringify(next)) } catch { /* storage unavailable */ } }
  return [value, save]
}

async function copy(text, say) {
  try { await navigator.clipboard.writeText(text); say?.('Copied to the clipboard.', 'success') }
  catch { say?.('Could not copy. Select the text and copy it by hand.', 'warn') }
}

/** Two or three resources, a time estimate, a small project and a checklist whose progress is remembered in this browser. */
export function LearningPlan({ path, who }) {
  const [done, setDone] = useStored(`pp_learn:${who}:${path.skill}`, [])
  const finished = done.filter(index => index < path.steps.length).length
  const toggle = index => setDone(done.includes(index) ? done.filter(item => item !== index) : [...done, index])
  return <details className="learnPlan">
    <summary><span>Learning path</span><span className="learnMeta">{path.estimate} · {finished}/{path.steps.length} steps</span></summary>
    <div className="progressTrack" aria-hidden="true"><i style={{ width: `${Math.round(100 * finished / path.steps.length)}%` }} /></div>
    <ul className="resourceList">{path.resources.map(resource => <li key={resource.url}><a href={resource.url} target="_blank" rel="noreferrer">{resource.title}</a><span className="chip">{resource.kind}</span></li>)}</ul>
    <p className="projectIdea"><strong>Small project</strong>{path.project}</p>
    <ul className="checklist">{path.steps.map((step, index) => <li key={step}><label><input type="checkbox" checked={done.includes(index)} onChange={() => toggle(index)} /><span>{step}</span></label></li>)}</ul>
  </details>
}

/** Interview questions, resume bullets and the public profile, all built from the repository's assessed evidence. */
export function GrowthPanel({ repository, auth, say, fail }) {
  const [interview, setInterview] = useState(null)
  const [resume, setResume] = useState(null)
  const [profile, setProfile] = useState(undefined)
  const [busy, setBusy] = useState('')
  const owner = repository.split('/')[0]?.toLowerCase()
  const ownsRepo = !!auth.user && auth.user.login.toLowerCase() === owner

  useEffect(() => {
    setInterview(null); setResume(null)
    if (!auth.user) { setProfile(null); return }
    request('/profile').then(found => setProfile(found || null)).catch(() => setProfile(null))
  }, [repository, auth.user?.login])

  async function run(name, task) { setBusy(name); try { await task() } catch (error) { fail(error) } finally { setBusy('') } }
  const loadInterview = () => run('interview', async () => setInterview(await request(`/interview?repository=${encodeURIComponent(repository)}`)))
  const loadResume = () => run('resume', async () => setResume(await request(`/resume?repository=${encodeURIComponent(repository)}`)))
  const publish = () => run('profile', async () => { setProfile(await postJson('/profile/publish', { repository })); say('Your verified skills page is live.', 'success') })
  const unpublish = () => run('profile', async () => { await postJson('/profile/unpublish'); setProfile(null); say('Your profile is no longer public.') })

  function printResume() {
    const win = window.open('', '_blank')
    if (!win) return say('Your browser blocked the print window. Allow pop-ups for this site and try again.', 'warn')
    const items = resume.bullets.map(bullet => `<li>${escapeHtml(bullet.text)} <a href="${escapeHtml(bullet.url)}">${escapeHtml(bullet.path)}</a></li>`).join('')
    win.document.write(`<!doctype html><meta charset="utf-8"><title>Resume bullets - ${escapeHtml(resume.repository)}</title>
      <style>body{font:14px/1.6 Georgia,serif;margin:48px;color:#102A43}h1{font-size:20px;margin:0}p{margin:4px 0 20px;color:#52606D}li{margin:8px 0}a{color:#1D4ED8;font-size:12px}</style>
      <h1>${escapeHtml(resume.projectLine)}</h1><p>github.com/${escapeHtml(resume.repository)} · commit ${escapeHtml(shortSha(resume.revision))} · every line links to the file that proves it</p><ul>${items}</ul>`)
    win.document.close(); win.focus(); win.print()
  }
  const resumeText = () => `${resume.projectLine}\n${resume.bullets.map(bullet => `- ${bullet.text}`).join('\n')}`

  return <section className="growth card" id="growth">
    <div className="growthHead"><h3>Turn your proof into something to show</h3>
      <p className="muted small">Everything here is built from the files ProofPath found in {repository}. Nothing is invented.</p></div>
    <div className="growthGrid">
      <div className="growthItem">
        <h4>Interview prep from your own code</h4>
        <p className="muted small">Questions about the files you actually wrote, not a generic list.</p>
        {!interview && <button type="button" className="secondary" disabled={!!busy} onClick={loadInterview}>{busy === 'interview' ? 'Reading your code…' : 'Generate questions'}</button>}
        {interview && <ol className="questionList">{interview.questions.map(item => <li key={item.path}>
          <p>{item.question}</p>
          <a href={item.url} target="_blank" rel="noreferrer">{item.path}</a>
          {item.lookFor && <span className="muted small">A strong answer: {item.lookFor}</span>}</li>)}</ol>}
        {interview && <p className="muted small">{interview.generatedBy === 'rules' ? 'Built from templates that quote your code.' : 'Written by the AI from your cited files.'}</p>}
      </div>
      <div className="growthItem">
        <h4>Evidence-backed resume bullets</h4>
        <p className="muted small">Only skills with cited files. Each bullet links to its proof.</p>
        {!resume && <button type="button" className="secondary" disabled={!!busy} onClick={loadResume}>{busy === 'resume' ? 'Drafting…' : 'Draft bullets'}</button>}
        {resume && (resume.bullets.length === 0
          ? <p className="muted small">No skill is demonstrated yet. Fix a gap and ProofPath will add bullets.</p>
          : <>
            <p className="projectLine">{resume.projectLine}</p>
            <ul className="bulletList">{resume.bullets.map(bullet => <li key={bullet.skill}>{bullet.text} <a href={bullet.url} target="_blank" rel="noreferrer">view file</a></li>)}</ul>
            <div className="rowActions"><button type="button" className="secondary" onClick={() => copy(resumeText(), say)}>Copy</button><button type="button" className="secondary" onClick={printResume}>Save as PDF</button></div>
            {resume.notYetProven.length > 0 && <p className="muted small">Not proven yet, so left out: {resume.notYetProven.map(pretty).join(', ')}.</p>}
          </>)}
      </div>
      <div className="growthItem">
        <h4>Public verified-skills profile</h4>
        <p className="muted small">A shareable page for recruiters, with a README badge. Public repositories you own only.</p>
        {!auth.user && <p className="muted small">{auth.configured ? <a href={`${API}/auth/github/login`}>Sign in with GitHub</a> : 'Sign in with GitHub'} to publish yours.</p>}
        {auth.user && profile && <>
          <p className="profileLink"><a href={profile.profileUrl} target="_blank" rel="noreferrer">{profile.profileUrl}</a></p>
          <p className="muted small">{profile.skills.length} verified skill{profile.skills.length === 1 ? '' : 's'} from {profile.repository}.{profile.repository.toLowerCase() !== repository.toLowerCase() ? ' Publish this repository to replace it.' : ''}</p>
          <div className="rowActions">
            <button type="button" className="secondary" onClick={() => copy(profile.badgeMarkdown, say)}>Copy README badge</button>
            {ownsRepo && profile.repository.toLowerCase() !== repository.toLowerCase() && <button type="button" className="secondary" disabled={!!busy} onClick={publish}>Publish this repo</button>}
            {ownsRepo && profile.repository.toLowerCase() === repository.toLowerCase() && <button type="button" className="secondary" disabled={!!busy} onClick={publish}>Refresh</button>}
            <button type="button" className="ghost" disabled={!!busy} onClick={unpublish}>Unpublish</button>
          </div>
        </>}
        {auth.user && !profile && (ownsRepo
          ? <button type="button" className="primary" disabled={!!busy} onClick={publish}>{busy === 'profile' ? 'Publishing…' : 'Publish my verified skills'}</button>
          : <p className="muted small">Choose a repository under {auth.user.login}/ to publish it.</p>)}
      </div>
    </div>
  </section>
}

/** The page behind /u/<login>. Public: no sign-in needed. */
export function PublicProfilePage({ login }) {
  const [state, setState] = useState({ loading: true, profile: null, error: '' })
  useEffect(() => {
    request(`/public/${encodeURIComponent(login)}`)
      .then(profile => setState({ loading: false, profile, error: '' }))
      .catch(error => setState({ loading: false, profile: null, error: error.message }))
  }, [login])
  const { loading, profile, error } = state
  return <div className="publicPage">
    <header className="publicBar"><a href="/" className="brand"><span>ProofPath</span></a><span className="tag">Verified skills</span></header>
    <main className="publicMain">
      {loading && <p className="muted">Loading…</p>}
      {!loading && !profile && <div className="card"><h1>No public profile</h1><p className="muted">{error || 'This profile has not been published.'}</p><a className="primary linkButton" href="/">Open ProofPath</a></div>}
      {profile && <>
        <div className="publicIntro">
          {profile.avatarUrl && <img src={profile.avatarUrl} alt="" width="72" height="72" />}
          <div>
            <h1>{profile.login}</h1>
            <p className="muted">Skills checked against the code in <a href={profile.repositoryUrl} target="_blank" rel="noreferrer">{profile.repository}</a>
              {profile.revision && <> at commit <code>{shortSha(profile.revision)}</code></>}. Every skill links to the files that prove it.</p>
          </div>
        </div>
        {profile.skills.length === 0 && <div className="card"><p className="muted">No skill is verified in the latest check.</p></div>}
        <div className="publicSkills">{profile.skills.map(skill => <article className="card publicSkill" key={skill.skill}>
          <header><h2>{skill.label}</h2><span className="chip chip-met">Verified</span></header>
          <p>{skill.explanation}</p>
          <ul>{skill.evidence.map(file => <li key={file.url}><a href={file.url} target="_blank" rel="noreferrer">{file.path}</a>{file.reason && <span className="muted small"> {file.reason}</span>}</li>)}</ul>
        </article>)}</div>
        <p className="muted small publicFoot">Checked by <a href="/">ProofPath</a>, which reads the repository itself instead of trusting a claimed skill. Want yours? <a href="/">Check your own code</a>.</p>
      </>}
    </main>
  </div>
}
