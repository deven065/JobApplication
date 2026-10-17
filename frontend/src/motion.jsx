import { useEffect, useMemo, useRef } from 'react'
import { m } from 'framer-motion'

// Every animation here moves only transform and opacity, which the browser composites on the GPU.
export const spring = { type: 'spring', stiffness: 380, damping: 30 }
export const ease = [0.22, 1, 0.36, 1]

export const fadeUp = {
  hidden: { opacity: 0, y: 18 },
  show: { opacity: 1, y: 0, transition: { duration: 0.55, ease } }
}
export const stagger = (gap = 0.07, delay = 0) => ({
  hidden: {},
  show: { transition: { staggerChildren: gap, delayChildren: delay } }
})
export const cardIn = {
  hidden: { opacity: 0, y: 26, scale: 0.97 },
  show: { opacity: 1, y: 0, scale: 1, transition: { duration: 0.5, ease } },
  exit: { opacity: 0, scale: 0.94, transition: { duration: 0.18 } }
}

/** Counts up to `value` by writing text directly, so React does not re-render on every frame. */
export function CountUp({ value, duration = 1.1, suffix = '' }) {
  const node = useRef(null)
  const from = useRef(0)
  useEffect(() => {
    const start = performance.now(), begin = from.current
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    let frame
    const tick = now => {
      const t = reduce ? 1 : Math.min(1, (now - start) / (duration * 1000))
      const eased = 1 - Math.pow(1 - t, 4)
      if (node.current) node.current.textContent = `${Math.round(begin + (value - begin) * eased)}${suffix}`
      if (t < 1) frame = requestAnimationFrame(tick); else from.current = value
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [value])
  return <span ref={node}>{`${from.current}${suffix}`}</span>
}

/** Small one-shot burst for the "assessment improved" moment. */
export function Confetti({ pieces = 26 }) {
  const bits = useMemo(() => Array.from({ length: pieces }, (_, index) => ({
    id: index,
    x: (Math.random() - 0.5) * 520,
    y: -40 - Math.random() * 160,
    rotate: (Math.random() - 0.5) * 540,
    delay: Math.random() * 0.15,
    color: ['#5eead4', '#818cf8', '#34d399', '#fbbf24', '#f472b6'][index % 5],
    round: index % 3 === 0
  })), [pieces])
  return <div className="confetti" aria-hidden="true">{bits.map(bit =>
    <m.span key={bit.id} style={{ background: bit.color, borderRadius: bit.round ? '50%' : 2 }}
      initial={{ x: 0, y: 0, opacity: 1, rotate: 0, scale: 1 }}
      animate={{ x: bit.x, y: [0, bit.y, bit.y + 220], opacity: [1, 1, 0], rotate: bit.rotate, scale: [1, 1, 0.6] }}
      transition={{ duration: 1.6, delay: bit.delay, ease: 'easeOut' }} />)}
  </div>
}

/** Moves a soft highlight under the pointer by updating two CSS variables (no React state). */
export function spotlight(event) {
  const box = event.currentTarget.getBoundingClientRect()
  event.currentTarget.style.setProperty('--mx', `${event.clientX - box.left}px`)
  event.currentTarget.style.setProperty('--my', `${event.clientY - box.top}px`)
}
