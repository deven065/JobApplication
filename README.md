# ProofPath — Career Evidence Coach

ProofPath compares real job requirements with evidence in a student's GitHub repository. Every demonstrated skill includes the source file, immutable commit link, and matched code signal. A missing signal means only `NOT_DEMONSTRATED` in the scanned scope.

## Run locally

Requires Java 17+ and Node.js 20+.

```powershell
# Terminal 1: backend
.\mvnw.cmd spring-boot:run

# Terminal 2: React application
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. The backend runs at `http://localhost:8081` because port 8080 is occupied locally.

The local H2 database is saved as `proofpath-data.mv.db` in the backend working directory. It preserves jobs, watches, current assessments, and scan history across restarts. Tests use an isolated in-memory database. For PostgreSQL, set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` before launching the backend.

## GitHub setup

Public repositories use GitHub's REST API directly. The application validates the repository and default branch, reads the commit SHA and recursive tree, fetches immutable Git blobs, and links evidence to the exact GitHub commit.

For private repositories or automatic webhook creation, create a GitHub fine-grained personal access token restricted to your demo repository. Grant **Contents: Read** and **Webhooks: Read and write**. Set it only in the terminal before starting Spring Boot—never commit it:

```powershell
$env:GITHUB_TOKEN = "your_token_here"
.\mvnw.cmd spring-boot:run
```

For a real GitHub push webhook, expose the backend using an HTTPS tunnel or deploy it. Set the public base URL before starting Spring Boot:

```powershell
$env:GITHUB_WEBHOOK_PUBLIC_BASE_URL = "https://your-public-https-url"
.\mvnw.cmd spring-boot:run
```

In React, first assess a repository, then select **Watch changes**. The normal mode checks GitHub every minute. Under **Advanced: GitHub push webhook**, you can enable a `push` webhook at `/api/webhooks/github`. Its HMAC secret is saved locally and verified on delivery. Repeated watch requests reuse an installed webhook.

## Demonstration loop

1. Enter a real job or internship source URL. If the site cannot be fetched, paste the posting text while retaining its source URL. Review and edit the detected skills.
2. Assess a real GitHub repository; open the evidence links pinned to the scanned SHA.
3. Select **Watch changes** to track the assessment.
4. Push a new SQL query file such as `queries/applications.sql` containing `SELECT id FROM applications;`.
5. GitHub push delivery or polling sees the new SHA. The backend rescans and recomputes every tracked job assessment for that repository.
6. React refreshes the latest assessment every 10 seconds and shows SQL as `DEMONSTRATED`, citing the changed file and new commit. The results also show scan coverage, next steps for gaps, and previous scans.

## Key APIs

- `POST /api/jobs/discover` — fetches and retains a job source URL and matched requirements.
- `GET /api/jobs` and `PUT /api/jobs/{id}/requirements` — list saved jobs and correct the detected skills.
- `POST /api/assessments` — creates a tracked job/repository assessment.
- `POST /api/repositories/watch` — enables polling and optionally creates a real GitHub webhook.
- `GET /api/repositories/watch` — restores watch status after reloading the browser.
- `POST /api/webhooks/github` — verified GitHub push callback.
- `GET /api/assessments/latest` — current automatically reassessed result for React.
- `GET /api/assessments/history` — recent assessments for the selected job and repository.

The scanner considers supported source/configuration files and inspects at most 80 relevant files per scan. The coverage display reports skipped files and partial GitHub trees. Skill matching is rule based: evidence is a code signal, not a complete evaluation of a candidate's ability.
