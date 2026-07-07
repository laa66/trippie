const DEFAULT_TILES_URL = 'http://localhost:8080'

/**
 * Validated tileserver origin. `VITE_TILES_URL` is a system-boundary input, so
 * it is parsed once here; a malformed value fails fast rather than producing a
 * broken map style URL later.
 *
 * An empty value (the docker/same-origin build) yields an empty base, so map
 * assets are fetched origin-relative (`/styles/...`) through the gui nginx,
 * which reverse-proxies them to tileserver-gl. `dev` keeps the localhost:8080
 * default so `npm run dev` still talks to the compose tileserver directly.
 */
export function tilesBaseUrl(): string {
  const raw = import.meta.env.VITE_TILES_URL ?? DEFAULT_TILES_URL
  if (raw === '') return ''
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    throw new Error(`Invalid VITE_TILES_URL: "${raw}"`)
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(`VITE_TILES_URL must be http(s): "${raw}"`)
  }
  return url.origin
}

/** MapLibre style descriptor served by our tileserver-gl (bundled, no CDN). */
export function tileStyleUrl(): string {
  return `${tilesBaseUrl()}/styles/basic-preview/style.json`
}
