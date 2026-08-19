import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

const STORAGE_KEY = 'trippie.selectedCategories'
const ALL_SLUGS = [
  'public_art',
  'monuments',
  'heritage',
  'sacred',
  'museums',
  'viewpoints',
  'architecture',
  'attractions',
]

/** Minimal in-memory localStorage stand-in, stubbed globally like useGeolocation's navigator mock. */
function mockLocalStorage(initial: Record<string, string> = {}) {
  const store = new Map(Object.entries(initial))
  const storage = {
    getItem: vi.fn((key: string) => (store.has(key) ? store.get(key)! : null)),
    setItem: vi.fn((key: string, value: string) => {
      store.set(key, value)
    }),
    removeItem: vi.fn((key: string) => store.delete(key)),
    clear: vi.fn(() => store.clear()),
  }
  vi.stubGlobal('localStorage', storage)
  return storage
}

beforeEach(() => {
  vi.resetModules()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useCategorySelection', () => {
  it('defaults to all 8 categories when storage is absent', async () => {
    mockLocalStorage()

    const { useCategorySelection, CATEGORY_SLUGS } = await import('@/composables/useCategorySelection')
    const { selected } = useCategorySelection()

    expect(CATEGORY_SLUGS).toEqual(ALL_SLUGS)
    expect(selected.value).toEqual(ALL_SLUGS)
  })

  it('loads a valid stored subset (persisted selection survives a reload)', async () => {
    mockLocalStorage({ [STORAGE_KEY]: JSON.stringify(['museums', 'monuments']) })

    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    const { selected } = useCategorySelection()

    expect(selected.value).toEqual(['museums', 'monuments'])
  })

  it('falls back to all 8 on corrupt JSON', async () => {
    mockLocalStorage({ [STORAGE_KEY]: '{not valid json' })

    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    const { selected } = useCategorySelection()

    expect(selected.value).toEqual(ALL_SLUGS)
  })

  it('falls back to all 8 when the stored array contains an unknown slug', async () => {
    mockLocalStorage({ [STORAGE_KEY]: JSON.stringify(['museums', 'not-a-real-slug']) })

    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    const { selected } = useCategorySelection()

    expect(selected.value).toEqual(ALL_SLUGS)
  })

  it('falls back to all 8 when the stored value is not an array', async () => {
    mockLocalStorage({ [STORAGE_KEY]: JSON.stringify({ museums: true }) })

    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    const { selected } = useCategorySelection()

    expect(selected.value).toEqual(ALL_SLUGS)
  })

  it('respects an empty stored array instead of overriding to all 8', async () => {
    mockLocalStorage({ [STORAGE_KEY]: JSON.stringify([]) })

    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    const { selected } = useCategorySelection()

    expect(selected.value).toEqual([])
  })

  it('toggle() flips membership and writes the new selection to localStorage', async () => {
    const storage = mockLocalStorage({ [STORAGE_KEY]: JSON.stringify(['museums']) })

    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    const { selected, isSelected, toggle } = useCategorySelection()

    toggle('monuments')
    expect(isSelected('monuments')).toBe(true)
    expect(selected.value).toEqual(['museums', 'monuments'])

    await nextTick()
    expect(JSON.parse(storage.setItem.mock.calls.at(-1)![1])).toEqual(['museums', 'monuments'])

    toggle('museums')
    expect(isSelected('museums')).toBe(false)
    expect(selected.value).toEqual(['monuments'])

    await nextTick()
    expect(JSON.parse(storage.setItem.mock.calls.at(-1)![1])).toEqual(['monuments'])
  })

  it('degrades to the default without throwing when localStorage throws on read', async () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => {
        throw new Error('storage disabled')
      }),
      setItem: vi.fn(),
    })

    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    const { selected } = useCategorySelection()

    expect(selected.value).toEqual(ALL_SLUGS)
  })
})
