import { ref, watch, type Ref } from 'vue'

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
  isSelected: (slug: CategorySlug) => boolean
  toggle: (slug: CategorySlug) => void
}

// Module-scoped singleton state: the filter modal and the map must observe the same selection.
const selected = ref<CategorySlug[]>(readStoredSelection())

watch(
  selected,
  (slugs) => writeStoredSelection(slugs),
  { deep: true },
)

function isSelected(slug: CategorySlug): boolean {
  return selected.value.includes(slug)
}

function toggle(slug: CategorySlug): void {
  selected.value = isSelected(slug)
    ? selected.value.filter((s) => s !== slug)
    : [...selected.value, slug]
}

/**
 * App-wide selected-category state, persisted to localStorage and validated on read.
 * Defaults to all 8 categories when storage is absent, corrupt, or contains an unknown slug.
 */
export function useCategorySelection(): UseCategorySelection {
  return { selected, isSelected, toggle }
}
