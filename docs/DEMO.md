# ProofPath: 5-minute demo script

The story: **a student finds out exactly what a real job wants, sees what their code does and does not prove, fixes one gap,
and watches ProofPath re-assess on its own.** Every step below uses a real posting and a real repository.

## Before you present (10 minutes, do it once)

- [ ] Open https://proofpath-ai.netlify.app and wait for it to load. The keep-alive workflow normally keeps the backend awake; the first call after a long idle can take about a minute.
- [ ] Sign in with GitHub (not as a guest) so you can pick repositories and open issues.
- [ ] Choose a **small public repository you own** that clearly lacks one skill (for example no SQL files). Note the missing skill.
- [ ] Pick a real posting from a public board (Greenhouse, Lever or Ashby links are read through their API). Paste its link into the job field.
- [ ] Run the full loop once as a rehearsal (below), then **revert your demo commit** so the gap is back for the real run.
- [ ] Record a screen capture of the rehearsal as a backup if the network misbehaves.

## The run (target 5:00)

| Time | Say | Do |
|---|---|---|
| 0:00 | "Students are told to build projects, but nobody tells them whether the projects prove what employers ask for." | Show the landing screen, sign in with GitHub. |
| 0:30 | "I paste a real job posting. ProofPath keeps the source link and quotes the sentence behind every requirement." | Paste the posting link, press **Read job posting**, point at the quote on a requirement. |
| 1:15 | "Now my own repository. I pick it from my account, including private ones if I allow it." | Choose the repository in **Your repositories**, press **Assess repository**. |
| 2:00 | "Each verdict cites the exact files, pinned to the commit. The gap is explained, not just flagged." | Open a skill card, click a cited file, then show the gap and its **Build next** step. |
| 2:45 | "One click turns the gap into work: an issue with a checklist in my own repo, plus a learning path." | Press **Fix this gap**, open the created GitHub issue, expand **Learning path**. |
| 3:15 | "I make the change and push. I never come back to press a button." | In the repository, commit the missing file (for example `queries/applications.sql` with a SELECT ... JOIN) and push. |
| 3:45 | "ProofPath saw the push, re-checked only the affected skill, and the verdict moved." | Back in ProofPath (it polls every minute; a webhook is instant): show **What changed**, the **Re-checked / Unchanged** chips and the updated score. |
| 4:15 | "The proof is something I can show: resume bullets that cite files, interview questions about my own code, and a public page." | Open **Turn your proof into something to show**, press **Draft bullets**, then **Generate questions**; if you published a profile, open `/u/<login>`. |
| 4:45 | "Several repos? They combine, and the best evidence per requirement wins." | Show **Portfolio**, one sentence. |
| 5:00 | Stop. | |

## If something goes wrong

- **Backend sleeping or slow:** say "the free tier is waking up", wait, and cut to the recorded rehearsal if it exceeds 30 seconds.
- **GitHub rate limit message:** you are signed in, so your own limit applies. If it still appears, wait a minute or use the recording.
- **The push is not picked up in time:** press **Assess repository** again to force a fresh scan. Mention that change detection (polling and webhooks) normally does this for you.
- **AI unavailable:** the keyword rules still produce verdicts and cited files; say so, because that fallback is deliberate.

## Questions judges ask

- *Why not just trust the LLM?* Every claim is checked: cited paths must be files the scanner found, and requirement quotes must appear verbatim in the posting.
- *Is private code safe?* Tokens are stored encrypted (AES-GCM), private repositories can never be published to a public profile, and the public page shows only demonstrated skills.
- *What happens on a push?* A webhook (or the one-minute poll) compares the old and new commit and re-assesses only the skills whose files changed.
