import { execSync } from 'node:child_process'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/*
 * Stamped into the bundle as __APP_VERSION__ so a bug report says which build
 * the reporter was running. deploy.sh builds on the host from a git checkout,
 * so `git describe` works there; `-dirty` flags a build made from uncommitted
 * files. Outside a checkout the commit part degrades to "unknown" rather than
 * failing the build.
 */
function appVersion() {
  let commit = 'unknown'
  try {
    commit = execSync('git describe --always --dirty --abbrev=7', {
      stdio: ['ignore', 'pipe', 'ignore'],
    }).toString().trim()
  } catch {
    // not a git checkout
  }
  return `${commit} ${new Date().toISOString().slice(0, 10)}`
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  define: {
    __APP_VERSION__: JSON.stringify(appVersion()),
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8000'
    }
  },
})
