import { afterEach, describe, expect, it, vi } from 'vitest'
import { tileStyleUrl, tilesBaseUrl } from '@/lib/tiles'

describe('tiles', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('empty VITE_TILES_URL resolves to a same-origin relative style url', () => {
    vi.stubEnv('VITE_TILES_URL', '')
    expect(tilesBaseUrl()).toBe('')
    expect(tileStyleUrl()).toBe('/styles/basic-preview/style.json')
  })

  it('absolute VITE_TILES_URL is used as the style origin', () => {
    vi.stubEnv('VITE_TILES_URL', 'http://localhost:8080')
    expect(tilesBaseUrl()).toBe('http://localhost:8080')
    expect(tileStyleUrl()).toBe('http://localhost:8080/styles/basic-preview/style.json')
  })

  it('rejects a malformed VITE_TILES_URL', () => {
    vi.stubEnv('VITE_TILES_URL', 'not-a-url')
    expect(() => tilesBaseUrl()).toThrow(/Invalid VITE_TILES_URL/)
  })

  it('rejects a non-http VITE_TILES_URL', () => {
    vi.stubEnv('VITE_TILES_URL', 'ftp://example.com')
    expect(() => tilesBaseUrl()).toThrow(/must be http/)
  })
})
