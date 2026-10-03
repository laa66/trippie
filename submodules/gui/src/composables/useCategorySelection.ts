import { ref, watch, type Ref } from 'vue'
import { useAuthStore } from '@/lib/authStore'
import { getSettings, putSettings, type ContentMode, type UserSettings } from '@/lib/settingsApi'

/** The frontend's hardcoded copy of the backend's category contract (frozen, no shared codegen). */
export const CATEGORY_SLUGS = [
  'public_art',
  'monuments',
  'heritage',
  'sacred',
  'museums',
  'viewpoints',
  'architecture',
  'attractions',
] as const

export type CategorySlug = (typeof CATEGORY_SLUGS)[number]

/** Polish display labels for the UI. Slugs stay the frozen English contract. */
export const CATEGORY_LABELS: Record<CategorySlug, string> = {
  public_art: 'Sztuka publiczna',
  monuments: 'Pomniki',
  heritage: 'Dziedzictwo',
  sacred: 'Obiekty sakralne',
  museums: 'Muzea',
  viewpoints: 'Punkty widokowe',
  architecture: 'Architektura',
  attractions: 'Atrakcje',
}

const STORAGE_KEY = 'trippie.selectedCategories'

function isCategorySlug(value: unknown): value is CategorySlug {
  return typeof value === 'string' && (CATEGORY_SLUGS as readonly string[]).includes(value)
}

/** Valid stored value: an array whose every element is a known slug (empty array included). */
function isValidStoredSelection(value: unknown): value is CategorySlug[] {
  return Array.isArray(value) && value.every(isCategorySlug)
}

function readStoredSelection(): CategorySlug[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === null) return [...CATEGORY_SLUGS]

    const parsed: unknown = JSON.parse(raw)
    return isValidStoredSelection(parsed) ? parsed : [...CATEGORY_SLUGS]
  } catch {
    return [...CATEGORY_SLUGS]
  }
}

function writeStoredSelection(slugs: CategorySlug[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(slugs))
  } catch {
    // localStorage unavailable (private mode, quota, ...) — selection stays in-memory only.
  }
}

export interface UseCategorySelection {
  /** Currently selected category slugs. Shared app-wide (module singleton). */
  selected: Ref<CategorySlug[]>
  /** The user's default content mode (server-side setting; meaningful while logged in). */
  defaultContentMode: Ref<ContentMode>
  /** Set when loading or saving settings failed; null otherwise. */
  error: Ref<string | null>
  isSelected: (slug: CategorySlug) => boolean
  toggle: (slug: CategorySlug) => void
  setContentMode: (mode: ContentMode) => void
}

// Module-scoped singleton state: the filter modal and the map must observe the same selection.
const selected = ref<CategorySlug[]>(readStoredSelection())
const defaultContentMode = ref<ContentMode>('BOTH')
const error = ref<string | null>(null)

const { isAuthenticated } = useAuthStore()

// Last state the server confirmed; a failed save reverts to it so the UI never disagrees with the server.
const DEFAULT_SETTINGS: UserSettings = { defaultContentMode: 'BOTH', selectedCategories: [...CATEGORY_SLUGS] }
let confirmed: UserSettings = DEFAULT_SETTINGS
// True once GET /settings succeeded for the current session. Until then local state is only a fallback:
// writing it would overwrite the user's real server settings.
let hydrated = false
// True while a GET /settings is in flight; a refused write retries hydration only when none is.
let hydrating = false
// Bumped by every hydrate and logout. An async result applies only if the session did not change.
let generation = 0
// Single-flight save: one PUT at a time, plus one trailing PUT if more changes arrived meanwhile.
let saving = false
let dirty = false

function sameSlugs(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((slug) => b.includes(slug))
}

function applyServer(settings: UserSettings): void {
  confirmed = settings
  const slugs = settings.selectedCategories.filter(isCategorySlug)
  // Skip an equal echo: a fresh array would re-trigger the nearby query (and abort the one in flight).
  if (!sameSlugs(selected.value, slugs)) {
    selected.value = slugs
  }
  defaultContentMode.value = settings.defaultContentMode
}

async function hydrate(): Promise<void> {
  const mine = ++generation
  hydrating = true
  try {
    const settings = await getSettings()
    if (mine === generation) {
      hydrated = true
      error.value = null
      applyServer(settings)
    }
  } catch {
    if (mine === generation) {
      error.value = 'Nie udało się wczytać ustawień.'
    }
  } finally {
    if (mine === generation) hydrating = false
  }
}

// Hydration trigger: whenever isAuthenticated becomes true (the boot refresh or a fresh login flips
// it after module init). Becoming false drops all server state back to the pre-auth localStorage
// fallback — which is never written while logged in.
watch(isAuthenticated, (authenticated) => {
  generation++
  saving = false
  dirty = false
  hydrated = false
  hydrating = false
  confirmed = DEFAULT_SETTINGS
  error.value = null
  if (authenticated) {
    void hydrate()
    return
  }
  selected.value = readStoredSelection()
  defaultContentMode.value = 'BOTH'
})
if (isAuthenticated.value) {
  void hydrate()
}

function revertToConfirmed(): void {
  const slugs = confirmed.selectedCategories.filter(isCategorySlug)
  if (!sameSlugs(selected.value, slugs)) {
    selected.value = slugs
  }
  defaultContentMode.value = confirmed.defaultContentMode
}

async function saveToServer(): Promise<void> {
  if (saving) {
    dirty = true
    return
  }
  saving = true
  const mine = generation
  try {
    do {
      dirty = false
      try {
        const stored = await putSettings({
          defaultContentMode: defaultContentMode.value,
          selectedCategories: [...selected.value],
        })
        if (mine !== generation) return
        confirmed = stored
        // With a newer change pending, the echo is stale; the trailing PUT settles the state.
        if (!dirty) {
          error.value = null
          applyServer(stored)
        }
      } catch {
        if (mine !== generation) return
        // Failure policy: revert to the last server-confirmed state (dropping pending changes) and surface an error.
        dirty = false
        revertToConfirmed()
        error.value = 'Nie udało się zapisać ustawień. Przywrócono poprzednie.'
      }
    } while (dirty)
  } finally {
    if (mine === generation) saving = false
  }
}

/**
 * False while logged in but not yet hydrated: nothing may be written then. The refused change is not
 * queued; instead hydration is retried (unless one is already in flight) so the next change succeeds.
 */
function canWrite(): boolean {
  if (isAuthenticated.value && !hydrated) {
    error.value = 'Ustawienia są wczytywane ponownie — zmiana nie została zapisana.'
    if (!hydrating) {
      void hydrate()
    }
    return false
  }
  return true
}

function isSelected(slug: CategorySlug): boolean {
  return selected.value.includes(slug)
}

function toggle(slug: CategorySlug): void {
  if (!canWrite()) return
  // Local state first (optimistic): the map re-queries immediately; the PUT never gates it.
  selected.value = isSelected(slug)
    ? selected.value.filter((s) => s !== slug)
    : [...selected.value, slug]
  if (isAuthenticated.value) {
    void saveToServer()
  } else {
    writeStoredSelection(selected.value)
  }
}

/** Content mode is a server-only setting: it has no localStorage fallback, so logged out it is not persisted. */
function setContentMode(mode: ContentMode): void {
  if (!canWrite()) return
  defaultContentMode.value = mode
  if (isAuthenticated.value) {
    void saveToServer()
  }
}

/**
 * App-wide selected-category state. Logged in: the server (`/api/auth/settings`) is the source of
 * truth. Logged out: localStorage fallback, validated on read — all 8 categories when storage is
 * absent, corrupt, or contains an unknown slug.
 */
export function useCategorySelection(): UseCategorySelection {
  return { selected, defaultContentMode, error, isSelected, toggle, setContentMode }
}
