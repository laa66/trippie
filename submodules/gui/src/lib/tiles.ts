const DEFAULT_TILES_URL = 'http://localhost:8080'

/**
 * Validated tileserver origin. `VITE_TILES_URL` is a system-boundary input, so
 * it is parsed once here; a malformed value fails fast rather than producing a
 * broken map style URL later.
 */
export function tilesBaseUrl(): string {
  const raw = import.meta.env.VITE_TILES_URL ?? DEFAULT_TILES_URL
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
