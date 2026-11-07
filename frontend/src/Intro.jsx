import { useEffect, useState } from 'react'

const SHOW_MS = 2900   // how long the logo stays before it fades out
const LEAVE_MS = 600   // length of the fade-out (matches .intro in styles.css)

const alreadySeen = () => {
  try { return sessionStorage.getItem('pp_intro') === '1' || window.matchMedia('(prefers-reduced-motion: reduce)').matches }
  catch { return false }
}

/**
 * Opening animation: the mark draws itself, then the name rises, then the screen fades into the app. It plays once per
 * browser session, can be skipped with a click or any key, and is skipped for people who prefer reduced motion.
 * Pure CSS keyframes (see "Intro" in styles.css), so it adds no animation library work to the first paint.
 */
export default function Intro() {
  const [phase, setPhase] = useState(() => (alreadySeen() ? 'gone' : 'show'))

  useEffect(() => {
    if (phase !== 'show') return undefined
    try { sessionStorage.setItem('pp_intro', '1') } catch { /* private mode: it just plays again next time */ }
    document.body.style.overflow = 'hidden'
    const leave = () => setPhase('leaving')
    const timer = window.setTimeout(leave, SHOW_MS)
    window.addEventListener('keydown', leave)
    return () => { window.clearTimeout(timer); window.removeEventListener('keydown', leave) }
  }, [phase])

  useEffect(() => {
    if (phase !== 'leaving') return undefined
    document.body.style.overflow = ''
    const timer = window.setTimeout(() => setPhase('gone'), LEAVE_MS)
    return () => window.clearTimeout(timer)
  }, [phase])

  if (phase === 'gone') return null
  return <div className={`intro ${phase === 'leaving' ? 'leaving' : ''}`} onClick={() => setPhase('leaving')} aria-hidden="true">
    <div className="introStack">
      <svg className="introMark" viewBox="1 5 46 35" fill="none" focusable="false">
        <path className="draw" pathLength="1" d="M6 35C15 35 15 25 24 25S32 14 39 14" stroke="#102A43" strokeWidth="2.6" strokeLinecap="round" />
        <circle className="pop a" cx="6" cy="35" r="3.3" fill="#fff" stroke="#102A43" strokeWidth="2.4" />
        <circle className="pop b" cx="24" cy="25" r="3.3" fill="#fff" stroke="#102A43" strokeWidth="2.4" />
        <circle className="ping" cx="39" cy="14" r="7.4" />
        <circle className="pop c" cx="39" cy="14" r="7.4" fill="#0F6E56" />
        <path className="tick" pathLength="1" d="M35.6 14.2l2.6 2.6 4.4-5.2" stroke="#fff" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <div className="introWord">Proof<em>Path</em></div>
    </div>
  </div>
}
