/**
 * The ProofPath mark: a path through two open points (a requirement, then the evidence) to a solid green point with a check
 * (the proof). Drawn inline so it scales with its container; the same artwork is in public/logo.svg.
 * The viewBox is cropped to the drawing, so `size` is its height.
 */
export default function Logo({ size = 28 }) {
  return <svg width={Math.round(size * 46 / 35)} height={size} viewBox="1 5 46 35" fill="none" aria-hidden="true" focusable="false">
    <path d="M6 35C15 35 15 25 24 25S32 14 39 14" stroke="#102A43" strokeWidth="2.6" strokeLinecap="round" />
    <circle cx="6" cy="35" r="3.3" fill="#fff" stroke="#102A43" strokeWidth="2.4" />
    <circle cx="24" cy="25" r="3.3" fill="#fff" stroke="#102A43" strokeWidth="2.4" />
    <circle cx="39" cy="14" r="7.4" fill="#0F6E56" />
    <path d="M35.6 14.2l2.6 2.6 4.4-5.2" stroke="#fff" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
}
