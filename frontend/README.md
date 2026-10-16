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

Open `http://localhost:5173`. Vite proxies `/api/*` to `http://localhost:8081`.

If the backend uses another port, change `server.proxy['/api'].target` in `vite.config.js`.

## Production connection

Copy `.env.example` to `.env.local` and set `VITE_API_BASE_URL` to the public backend `/api` URL. The backend must be started with `FRONTEND_ORIGIN` set to the same frontend origin, for example `https://proofpath.example.com`. Never place database passwords, GitHub tokens, or webhook secrets in this frontend directory: Vite exposes every `VITE_*` value to the browser.
