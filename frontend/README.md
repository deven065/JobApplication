# ProofPath React frontend

This is intentionally separate from the Spring Boot backend so each can be developed and deployed independently.

## Start locally

First start the backend from the repository root:

```powershell
.\mvnw.cmd spring-boot:run
```

Then, in a second terminal:

```powershell
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api/*` to `http://localhost:8080`.

If the backend uses another port, change `server.proxy['/api'].target` in `vite.config.js`. For production, serve this built frontend independently and point its API base URL to the deployed backend (or have a reverse proxy route `/api`).
