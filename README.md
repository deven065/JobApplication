<div align="center">

# ProofPath — AI Career Evidence Coach

**Show what your portfolio proves, and what it does not.**

*A Kurukshetra Hackathon project · Challenge: AI Career Coach (sponsored by Worcflow)*

![Java](https://img.shields.io/badge/Java-17+-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)
![React](https://img.shields.io/badge/React-18-61DAFB)
![AI](https://img.shields.io/badge/AI-Groq%20%7C%20Gemini%20%7C%20Claude-8A2BE2)
![Database](https://img.shields.io/badge/DB-PostgreSQL-336791)

</div>

---

## Table of contents

- [About the project](#about-the-project)
- [Key features](#key-features)
- [How it works](#how-it-works)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [Deployment](#deployment)
- [Demo walkthrough](#demo-walkthrough)
- [Brief coverage](#brief-coverage)
- [Trust and traceability](#trust-and-traceability)
- [API reference](#api-reference)
- [Project structure](#project-structure)
- [Testing](#testing)
- [Limitations](#limitations)
- [Author](#author)

---

## About the project

ProofPath is my project for the **Kurukshetra Hackathon**, built for the **AI Career Coach** challenge.

Students often list skills on a resume that their public code does not show. ProofPath compares the requirements of a **real job or internship posting** with the evidence in a student's **actual GitHub repository**. It explains each gap and points to the exact files and commits behind every conclusion.

It also keeps watching. When the student pushes new code, ProofPath detects the change and reassesses the affected skills automatically. This closes the loop the challenge asks for:

> **job requirements → repository evidence → gaps → code change → updated assessment**

A missing signal means *"not demonstrated in this scan"*. It is never treated as proof that the student lacks the skill.

## Key features

| Feature | What it does |
|---|---|
| **Real job discovery** | Searches real postings on public **Greenhouse, Lever and Ashby** job boards (key-free APIs behind company careers pages) and imports one with its source link. A posting URL or pasted text also works. The AI extracts each required skill together with the **exact phrase** from the posting. |
| **Evidence from real code** | Uses the GitHub REST API to read the repository at a specific commit. Every piece of evidence links to the **file and line at that commit**. |
| **AI review of the evidence** | An LLM reads the code around each keyword match and rates each skill `DEMONSTRATED`, `PARTIAL` or `NOT_DEMONSTRATED`, with a confidence level, an explanation and a concrete next step. |
| **Gap explanations** | A summary of how well the repository fits the role, plus a small, practical project suggestion for each gap. |
| **Automatic change detection** | Polls GitHub every minute, or receives a signed GitHub **push webhook**, then rescans automatically. GitHub's compare API lists the changed files, and **only the skills those files affect are reassessed**; the rest keep their verdicts. Evidence from the commit is tagged *changed in this commit*. No manual refresh. |
| **Demo evidence report** | One click downloads a Markdown report with the job source, repository evidence, gaps, the repository change and the before/after assessment, the evidence the brief asks for. |
| **Sign in with GitHub** | GitHub OAuth sign-in: pick from your own repositories (private ones too, if you allow it), and scan with your own GitHub rate limit. Tokens are stored encrypted. |
| **Email accounts** | Sign up and sign in with an email and password (no GitHub needed). Passwords are stored only as salted PBKDF2 hashes, and repeated wrong passwords lock the account for ten minutes. Email accounts can assess any public repository; picking your own repositories, private repositories, opening issues and the public profile need a GitHub sign-in. |
| **Portfolio of repositories** | Combine up to five repositories for one job. Each requirement takes the best evidence from any of them, cited with its repository, and the combined score is shown next to each repository's own. |
| **What changed feed** | Lists every skill that moved between scans (Gap → Demonstrated), with the commit and the number of changed files. |
| **Jobs you're ready for** | Ranks every open posting on the company job boards you choose by how many of its requirements your repository already proves (*"You meet 5 of 6 — missing: SQL"*). One click imports a posting and runs the full assessment. |
| **Fix this gap** | Opens a GitHub Issue in your own repository with a checklist for the missing skill and starts watching the repository. Close it with a commit and ProofPath re-checks that skill on its own. |
| **Learning path per gap** | Two official resources, a time estimate, a small proof project and a step checklist for every missing skill. Your progress is remembered in the browser. |
| **Interview prep from your code** | Questions about the files you actually wrote (AI-written when a key is set, otherwise templates that quote your code), each with what a strong answer covers. |
| **Public verified-skills profile** | A shareable page at `/u/<github-login>` listing each demonstrated skill with links to the exact files (pinned to the scanned commit), plus a README badge. Only public repositories you own can be published. |
| **Evidence-backed resume bullets** | Bullets written only from demonstrated skills, each naming its file. Copy them or save as PDF. |
| **History** | Every assessment is stored, so you can compare before and after a commit. |
| **Free AI options** | Works with free **Groq** or **Google Gemini** keys, or with **Anthropic Claude**. Without a key it falls back to keyword rules. |

## How it works

```mermaid
flowchart LR
    A[Job posting URL / text] -->|AI extracts skills + quotes| B[Job requirements]
    C[GitHub repository @ commit SHA] -->|Keyword scanner picks candidate files| D[Candidate evidence]
    B --> E{AI reviewer}
    D --> E
    E -->|Guardrails verify citations| F[Assessment: verdicts, gaps, next steps]
    G[New commit pushed] -->|Webhook or 1-minute polling| C
    F --> H[React dashboard + history]
```

1. **Discover.** The student searches a company's public job board, or gives a posting URL or text. The backend fetches it (blocking private and local network addresses for URLs) and strips the HTML. The AI maps the text onto the skill taxonomy and quotes the phrase that justifies each skill.
2. **Scan.** The scanner resolves the branch to a commit SHA, reads the recursive file tree, and inspects up to 80 relevant source and configuration files. Keyword rules pick the candidate files for each skill and capture the code around each match.
3. **Review.** The AI judges whether each candidate file really shows the skill, which filters out false positives such as "delete" in a comment or `github.com` in a URL.
4. **Verify.** Server-side guardrails accept a positive verdict only if it cites a file that was actually scanned (see [Trust and traceability](#trust-and-traceability)).
5. **Watch.** On every new commit, all tracked assessments for that repository are recomputed and stored in history. The UI refreshes every 10 seconds.

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 17+, Spring Boot 4.1 (Web MVC, JDBC, Scheduling) |
| AI | Groq / Google Gemini (OpenAI-compatible API, JSON mode), Anthropic Claude (`anthropic-java` SDK, structured outputs) |
| Integrations | GitHub REST API, GitHub push webhooks (HMAC-SHA256 verified) |
| Persistence | PostgreSQL (in-memory H2 only for the automated tests) |
| Frontend | React 18, Vite 5 |
| Testing | JUnit 5, Mockito, AssertJ, Spring Boot Test |

## Getting started

### Prerequisites

- **Java 17+**
- **Node.js 20+**
- **Docker** (to run PostgreSQL), or your own PostgreSQL 14+ server
- *(Recommended)* A free AI key from [Groq](https://console.groq.com/keys) or [Google AI Studio](https://aistudio.google.com/apikey)

### 1. Clone the repository

```bash
git clone https://github.com/deven065/JobApplication.git
```

### 2. Add your AI key

Copy `.env.example` to `.env` in the project root and add **one** key. `.env` is gitignored and is read however the backend is started (IntelliJ, VS Code or the terminal).

```properties
GROQ_API_KEY=gsk_your_key_here
```

### 3. Start PostgreSQL

```bash
docker compose up -d db
```

This starts PostgreSQL on port **5433** (database, user and password are all `proofpath`), which is what the backend connects to by default. Its data is kept in the `proofpath-db` Docker volume. To use your own PostgreSQL server instead, see [Using PostgreSQL](#using-postgresql).

### 4. Start the backend (port 8081)

```powershell
.\mvnw.cmd spring-boot:run
```

On macOS or Linux, use `./mvnw spring-boot:run`. The log should show:

```
AI coach enabled: groq:openai/gpt-oss-120b
```

### 5. Start the frontend (port 5173)

```powershell
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**. Vite forwards `/api` requests to the backend. A badge on the page shows whether AI review is on.

> **Note:** Run only one backend at a time (port 8081). Stop the IntelliJ run before starting from a terminal, and the other way round. If the backend fails with *Connection refused*, PostgreSQL is not running: start it with `docker compose up -d db`.

## Configuration

Every setting can be supplied as an environment variable or a line in `.env`.

### AI providers

| Variable | Default | Purpose |
|---|---|---|
| `GROQ_API_KEY` | — | Free, fastest. Recommended for the demo |
| `GEMINI_API_KEY` | — | Free alternative with higher token limits |
| `ANTHROPIC_API_KEY` | — | Paid. Strongest reviews |
| `GROQ_MODEL` | `openai/gpt-oss-120b` | Groq model override |
| `GEMINI_MODEL` | `gemini-2.5-flash` | Gemini model override |
| `ANTHROPIC_MODEL` | `claude-opus-5-5` | Claude model override |
| `AI_PROVIDER` | `auto` | Forces `groq`, `gemini` or `claude` when several keys are set. `auto` uses the first configured key in the order Groq → Gemini → Claude |

Free-tier limits change often. Groq's free tier allows few tokens per minute, so ProofPath sends it at most 3 files per skill, with about 700 characters of code from each.

### GitHub

| Variable | Purpose |
|---|---|
| `GITHUB_TOKEN` | Optional. Raises GitHub's API rate limit and allows private repositories. Use a fine-grained token with **Contents: Read** (and **Webhooks: Read and write** for automatic webhook creation) |
| `GITHUB_WEBHOOK_PUBLIC_BASE_URL` | Public HTTPS URL of the backend (for example, a tunnel). Needed only to install a push webhook |

### Privacy: no history between sessions

ProofPath keeps no history between sessions, and one visitor can never see another's jobs or scans.

- **Everyone has a private identity.** Signed-in users are identified by their session; everyone else gets a random guest id in a browser-session cookie. Jobs, assessments and scan history belong to that identity, and every endpoint that takes a job id or a repository name checks it.
- **A session's data is deleted when the session ends:** on sign-out, at the start of a new sign-in, after 24 hours for signed-in sessions, and after one hour without activity for guests. A cleaner runs every ten minutes. Deleted: saved jobs, assessments, scan history, repository watches and cached scans.
- **What stays:** the account itself (name, hashed password or encrypted GitHub token) and a *published* profile page, which keeps its own snapshot of the verified skills because you chose to make it public.
- Jobs saved before ownership existed belonged to nobody and are deleted when the server starts.

### Account emails (optional)

| Variable | Purpose |
|---|---|
| `RESEND_API_KEY` | Free key from [resend.com](https://resend.com). Turns on confirm-your-email and password-reset links |
| `EMAIL_FROM` | Sender, default `ProofPath <onboarding@resend.dev>`. Resend's test sender only delivers to your own Resend account address, so verify a domain to email other people |

Without a key nothing breaks: new email accounts start verified and the "Forgot your password?" link is hidden.

### Sign in with GitHub (optional)

| Variable | Purpose |
|---|---|
| `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET` | From a GitHub **OAuth App** (github.com → Settings → Developer settings → OAuth Apps → New) |
| `APP_BASE_URL` | Where users open the app; the OAuth callback is `{APP_BASE_URL}/api/auth/github/callback`. Local: `http://localhost:5173`. Render: `https://proofpath-8r5b.onrender.com` |
| `SESSION_SECRET` | Long random string that encrypts stored GitHub tokens (`render.yaml` generates one) |

Opening issues needs one more permission: the sign-in screen's *Allow ProofPath to open issues* link asks for GitHub's `public_repo` scope (or `repo` together with private-repository access). In the OAuth App settings, untick *Expire user access tokens* so tokens do not lapse after 8 hours.

GitHub allows one callback URL per OAuth App, so create **two apps**: one with callback `http://localhost:5173/api/auth/github/callback` for development and one with your Render URL for production. Sign-in asks only for `read:user`; *Include private repositories* additionally asks for `repo`. Without these variables the app works as before and the sign-in button is hidden.

### Database

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5433/proofpath` | PostgreSQL URL. Accepts `jdbc:postgresql://…` or a platform URL such as `postgres://user:pass@host/db` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `proofpath` / `proofpath` | Credentials (not needed when they are inside a `postgres://` URL) |
| `DATABASE_POOL_SIZE` | `5` | Maximum database connections; small enough for free database plans |
| `FRONTEND_ORIGIN` | `http://localhost:5173` | Allowed CORS origin(s) for a deployed frontend |

The four tables (`coach_jobs`, `coach_watches`, `coach_assessments`, `coach_history`) are created automatically on first start. To start with an empty local database, run `docker compose down -v` and then `docker compose up -d db`.

### Using PostgreSQL

PostgreSQL is the only database the application uses. Pick one way to run it.

**Option A: everything in Docker (easiest).** `docker-compose.yml` starts the app plus its own PostgreSQL. API keys are read from `.env`.

```bash
docker compose up --build
```

The app runs at http://localhost:8081. The database listens on host port 5433 (user `proofpath`, password `proofpath`, database `proofpath`), and its data persists in the `proofpath-db` Docker volume.

**Option B: your own PostgreSQL server** (local, Docker or hosted). Create a database, then add the connection to `.env`:

```properties
DATABASE_URL=jdbc:postgresql://localhost:5432/proofpath
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=your_password
```

Start the backend as usual. The four `coach_*` tables are created automatically on first start. The automated tests never touch PostgreSQL: they use an in-memory H2 database in PostgreSQL mode.

## Deployment

The `Dockerfile` builds **one container** that serves the React app and the API from the same URL. CORS stays simple, and the GitHub webhook URL is just your site's URL. The image contains no secrets: keys are supplied at run time, and the container reads the `PORT` variable that hosting platforms set. It runs as a non-root user and fits a 512 MB instance (about 250 MB in use).

### Recommended: Render + Neon (free)

| Piece | Service | Why |
|---|---|---|
| App container | [Render](https://render.com) free web service | Builds the `Dockerfile` straight from GitHub, HTTPS included, redeploys on every push |
| Database | [Neon](https://neon.tech) free PostgreSQL | Data survives redeploys (a container's own disk does not), no expiry on the free plan |

**1. Create the database.** Sign up at Neon, create a project, and copy the connection string from the dashboard. It looks like `postgres://user:password@ep-xxxx.aws.neon.tech/neondb?sslmode=require`. ProofPath accepts this format directly, and `jdbc:postgresql://` URLs work too. The tables are created automatically on first start.

**2. Push the code to GitHub.** Keys must never be committed: they belong in `.env` locally and in Render's settings online. The test suite fails if a key appears in the source.

**3. Deploy on Render.** Use **New → Blueprint** and pick the repository. Render reads `render.yaml` and asks for each value:

| Variable | Value |
|---|---|
| `GROQ_API_KEY` | your Groq key |
| `DATABASE_URL` | the Neon connection string |
| `GITHUB_TOKEN` | *(optional)* fine-grained token with Contents read. Raises GitHub's limit from 60 to 5,000 requests/hour |
| `GITHUB_WEBHOOK_PUBLIC_BASE_URL` | *(optional)* your Render URL, e.g. `https://proofpath.onrender.com`. Add it after the first deploy |

The first build takes about 5–8 minutes. Then open `https://<your-service>.onrender.com`; the health check uses `/api/ai/status`. Without the blueprint, choose **New → Web Service**, pick the repository, select **Docker** and the **Free** plan, and add the same variables.

**Free-tier note:** Render's free service sleeps after about 15 minutes without traffic and takes roughly a minute to wake up. While it sleeps, change polling pauses and webhook deliveries can time out. Open the site a couple of minutes before you present. For an always-on host, use [Railway](https://railway.app) (low monthly cost; it also offers PostgreSQL) or [Koyeb](https://www.koyeb.com); both run the same `Dockerfile` with the same variables.

### Run the container yourself

```bash
docker build -t proofpath .
```

```bash
docker run -p 8081:8081 --env-file .env -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/proofpath proofpath
```

Inside a container `localhost` is the container itself, so point `DATABASE_URL` at `host.docker.internal` to reach a PostgreSQL server on your computer. To run the app together with its own PostgreSQL, use `docker compose up --build` (see [Using PostgreSQL](#using-postgresql)).

### Frontend on Netlify, API on Render

Netlify only hosts static files, so the Spring Boot API stays on Render (or any host that runs the Docker image) and Netlify serves the React app. `netlify.toml` forwards `/api/*` to the backend, so the browser sees one origin and the sign-in cookie works.

1. **Netlify:** Add new site → Import from GitHub → pick the repository. Leave **Base directory**, build command and publish directory empty: everything comes from `netlify.toml` in the repository root. Deploy.
2. **Render environment** (Environment tab of the service), then redeploy:

| Variable | Value |
|---|---|
| `APP_BASE_URL` | `https://proofpath-ai.netlify.app` |
| `FRONTEND_ORIGIN` | `https://proofpath-ai.netlify.app` |

3. **GitHub OAuth App:** set the homepage URL to the Netlify URL and add the redirect URI `https://proofpath-ai.netlify.app/api/auth/github/callback`.
4. If your Render URL is not `https://proofpath-8r5b.onrender.com`, change the `to` URL in `netlify.toml`.

Notes: Netlify cuts proxied requests at about 26 seconds, which is enough for an assessment but not for a cold Render start. Open the site once to wake the backend. A GitHub webhook can still point straight at the Render URL (`GITHUB_WEBHOOK_PUBLIC_BASE_URL`).

The repository also ships `.github/workflows/keepalive.yml`, which pings the live site every 10 minutes so Render's free tier does not sleep (free for public repositories). The 5-minute demo script is in [docs/DEMO.md](docs/DEMO.md).

## Demo walkthrough

1. **Choose a job.** Enter the role, company and the posting's URL, then click **Read job posting**. If the site blocks fetching, paste the posting text; the source URL is still kept. Review the detected skills and their quotes.
2. **Assess a repository.** Enter `owner/repository` and click **Assess repository**. Open the evidence links; each one points to the scanned commit.
3. **Watch changes.** Click **Watch changes**. GitHub is checked every minute, or immediately with a webhook.
4. **Make a real change.** Push a commit that adds a SQL file, for example `queries/applications.sql`:
   ```sql
   SELECT id, company, status FROM applications WHERE status = 'APPLIED';
   ```
5. **See the updated assessment.** Within about a minute, SQL changes from *Not demonstrated* to *Evidence found* and cites the new file and commit. The page shows what changed since the previous commit, and the history keeps both scans.

## Brief coverage

How each requirement of the **W-PS-2 AI Career Coach** brief is met:

| Brief requirement | Where it lives |
|---|---|
| Discover real job or internship requirements, keeping source links | Job board search and import (Greenhouse, Lever, Ashby) or a posting URL; the source link is stored on every job and every skill quotes the posting |
| Analyse the student's actual repositories, citing files or code | GitHub scan pinned to a commit SHA; every verdict lists permalinks to the file and line |
| Compare evidence with requirements and explain the gaps | Per-skill verdict, confidence, explanation and a concrete next step, plus a coach summary |
| Detect changes (polling or webhooks) and reassess the **affected** skills | 60-second polling and signed push webhooks; GitHub's compare API decides which skills to re-check, and the rest are carried over |
| Demonstrate the loop | The four-step tracker on the page: job requirements → repository evidence → gaps → code change and updated assessment |
| SQL "not demonstrated" until a commit adds queries, then cite the changed files | The changed file is tagged *changed in this commit*, SQL is marked *Re-checked* and *Improved*, and the change panel lists the commit's files |
| A missing signal means "not demonstrated in this scope" | Shown on every gap and in the report; never worded as a missing skill |
| No static, manually refreshed report | The page updates itself after a push; Rescan is optional |
| Demo evidence deliverable | **Download evidence report**: job source, evidence, gaps, the change and the revised assessment |

## Trust and traceability

The AI is never the only source of truth. `AssessmentService` checks every result against the scan:

- **No evidence, no claim.** A skill with no scanned candidate file is always `NOT_DEMONSTRATED`, whatever the model says.
- **Citations are checked.** A positive verdict that does not cite a scanned file is downgraded to `PARTIAL` with low confidence.
- **Requirement quotes are checked.** A requirement whose quote does not appear word for word in the posting is dropped.
- **Rejected signals stay visible.** Keyword matches the AI rejected are still shown, so the student can see what was checked.
- **Coverage is reported.** The UI shows how many files were read and skipped, and whether GitHub returned a partial file tree.
- **Graceful fallback.** If the AI is unavailable, rate-limited or returns malformed output, the app uses keyword rules and says so.
- **Secure webhooks.** GitHub push payloads are verified with an HMAC-SHA256 signature before any rescan.

## API reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/jobs/discover` | Read a job posting and extract requirements with source quotes |
| `GET` | `/api/jobs` | List saved jobs |
| `PUT` | `/api/jobs/{id}/requirements` | Correct the detected skills |
| `GET` | `/api/jobs/demo` | Load a sample role |
| `POST` | `/api/assessments` | Scan a repository and assess it against a job |
| `GET` | `/api/assessments/latest` | Latest (automatically reassessed) result |
| `GET` | `/api/assessments/history` | Previous assessments for a job and repository |
| `GET` | `/api/repositories/resolve` | Validate a repository and get its default branch |
| `POST` | `/api/repositories/watch` | Start change tracking (polling, optional webhook) |
| `GET` | `/api/repositories/watch` | Current watch status |
| `POST` | `/api/webhooks/github` | Signed GitHub push callback |
| `GET` | `/api/ai/status` | Active AI provider and model |
| `POST` | `/api/gaps/issue` | Opens a GitHub issue with a checklist for one gap (signed in) and watches the repository |
| `GET` | `/api/learning-paths` | Resources, estimate, project and steps per skill |
| `GET` | `/api/interview?repository=owner/repo` | Interview questions about the repository's cited files |
| `GET` | `/api/resume?repository=owner/repo` | Resume bullets from demonstrated skills only |
| `GET` `POST` | `/api/profile` · `/api/profile/publish` · `/api/profile/unpublish` | Manage your public profile (signed in) |
| `GET` | `/api/public/{login}` · `/api/public/{login}/badge.svg?skill=SQL` | Public profile and README badge |
| `GET` | `/api/jobs/ready?repository=owner/repo&source=GREENHOUSE&boards=stripe,airbnb&q=engineer` | Open postings ranked by how much of each one the repository proves |
| `POST` | `/api/auth/register` · `/api/auth/login` | Email and password sign-up and sign-in (`{name, email, password}`) |
| `POST` | `/api/auth/forgot` · `/api/auth/reset` · `/api/auth/verify` · `/api/auth/resend-verification` | Password reset and email confirmation (need `RESEND_API_KEY`) |
| `GET` | `/api/assessments/combined?jobId=…&repositories=a/b,c/d` | One job judged against several assessed repositories |
| `GET` | `/api/auth/status` · `/api/me/repos` | Signed-in user and their repositories |
| `GET` | `/api/auth/github/login` → `/api/auth/github/callback` | GitHub OAuth sign-in (`POST /api/auth/logout` signs out) |
| `GET` | `/api/jobs/boards?source=GREENHOUSE&board=stripe&q=engineer` | Search a public Greenhouse, Lever or Ashby job board |
| `POST` | `/api/jobs/import` | Import one board posting (`source`, `board`, `jobId`) with its source link |

## Project structure

```
JobApplication/
├── src/main/java/com/deven/firstjobapplication/coach/
│   ├── AiCoach.java                # LLM providers, prompts, structured responses
│   ├── AssessmentService.java      # Assessment logic, guardrails, reassessment
│   ├── CareerCoachController.java  # REST API and webhook endpoint
│   ├── GitHubPortfolioService.java # GitHub scanning and evidence collection
│   ├── JobDiscoveryService.java    # Posting fetch and requirement extraction
│   ├── RepositoryMonitor.java      # Scheduled change detection (polling)
│   ├── SkillMatcher.java           # Keyword rules for candidate evidence
│   ├── StateStore.java             # PostgreSQL persistence
│   └── Models.java, Skill.java     # Domain records and skill taxonomy
├── src/main/resources/application.properties
├── src/test/java/...               # Unit and integration tests
├── frontend/                       # React + Vite dashboard
└── .env.example                    # Template for local API keys
```

## Testing

```powershell
.\mvnw.cmd test
```

The tests cover the full job → assess → watch → reassess flow, persistence across restarts, the AI guardrails (with a mocked model), the fallback to keyword rules, and the HTTP API. The tests use in-memory databases and never call a real AI provider.

## Limitations

- The skill taxonomy is intentionally small: Java, Spring Boot, REST APIs, SQL, PostgreSQL, React, Docker, Testing and Git.
- Each scan inspects up to 80 relevant files with supported extensions. Larger repositories are reported as partially scanned.
- Evidence is a signal from code, not a full evaluation of a person's ability.
- Free AI tiers have rate limits. When a limit is reached, the app uses keyword rules for that scan.

## Author

**Deven** · GitHub: [@deven065](https://github.com/deven065)

Built for the **Kurukshetra Hackathon**: AI Career Coach challenge, sponsored by **Worcflow**.
