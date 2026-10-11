import type { ExpressionSpecification, CircleLayerSpecification } from '@maplibre/maplibre-gl-style-spec'
import type { Feature, FeatureCollection, Point } from 'geojson'
import { CATEGORY_SLUGS, type CategorySlug } from '@/composables/useCategorySelection'
import type { NearbyItem } from '@/lib/nearbyApi'

export const POI_SOURCE_ID = 'nearby-pois'
export const POI_LAYER_ID = 'nearby-pois-circles'

/**
 * The POI palette is NOT held here. MapLibre takes paint colours as style values rather than CSS,
 * so this module cannot write `var(--...)` into the layer — but hard-coding hexes would also put
 * the map outside the design system and out of reach of the planned dark theme. The compromise:
 * the only thing this file owns is the NAME of each custom property; the values live beside every
 * other colour in `src/theme.css`, and are read off the document once, when the layer is built.
 *
 * Keyed by the frozen slug contract, so the compiler forces every slug to name a property.
 */
const CATEGORY_COLOR_PROPERTIES: Record<CategorySlug, string> = {
  public_art: '--app-poi-public-art',
  monuments: '--app-poi-monuments',
  heritage: '--app-poi-heritage',
  sacred: '--app-poi-sacred',
  museums: '--app-poi-museums',
  viewpoints: '--app-poi-viewpoints',
  architecture: '--app-poi-architecture',
  attractions: '--app-poi-attractions',
}

/** Colour of a feature whose category is not one of the 8 slugs — the DB CHECK makes this unreachable. */
export const UNKNOWN_CATEGORY_PROPERTY = '--app-poi-unknown'
/** Ring drawn around every circle; it is what separates a dot from the basemap underneath it. */
export const STROKE_COLOR_PROPERTY = '--app-poi-stroke'

export interface PoiPalette {
  categories: Record<CategorySlug, string>
  unknown: string
  stroke: string
}

/** Every custom property the layer needs, in one list, so a caller can check them all at once. */
export const POI_COLOR_PROPERTIES: readonly string[] = [
  ...Object.values(CATEGORY_COLOR_PROPERTIES),
  UNKNOWN_CATEGORY_PROPERTY,
  STROKE_COLOR_PROPERTY,
]

/**
 * Resolves the palette off the document's computed style. Called once per map (the layer is added
 * once and never re-added), so this costs a single style resolution for the whole session: no
 * listener, no subscription, nothing to tear down, and nothing on the render path.
 *
 * Empty means the property is not defined, which MapLibre would accept as a broken paint value and
 * render as invisible POIs — so it throws instead.
 */
export function readPoiPalette(root: Element = document.documentElement): PoiPalette {
  const computed = getComputedStyle(root)
  const read = (property: string): string => {
    const value = computed.getPropertyValue(property).trim()
    if (value === '') {
      throw new Error(`readPoiPalette: CSS custom property "${property}" is not defined`)
    }
    return value
  }

  const categories = {} as Record<CategorySlug, string>
  for (const slug of CATEGORY_SLUGS) {
    categories[slug] = read(CATEGORY_COLOR_PROPERTIES[slug])
  }

  return { categories, unknown: read(UNKNOWN_CATEGORY_PROPERTY), stroke: read(STROKE_COLOR_PROPERTY) }
}

/**
 * A MapLibre `match` expression on the `category` feature property. Built by
 * iterating the slug contract, so it covers every slug by construction and
 * throws loudly if a slug is missing a colour.
 */
export function buildCategoryColorExpression(
  colors: Partial<Record<CategorySlug, string>>,
  fallback: string,
): ExpressionSpecification {
  const branches: string[] = []
  for (const slug of CATEGORY_SLUGS) {
    const color = colors[slug]
    if (color === undefined) {
      throw new Error(`buildCategoryColorExpression: no colour for category "${slug}"`)
    }
    branches.push(slug, color)
  }
  // Dynamically assembled, so the tuple shape can't be inferred as a valid
  // `match` — the branches are correct by construction (2-arity, string outputs).
  return ['match', ['get', 'category'], ...branches, fallback] as unknown as ExpressionSpecification
}

export function categoryColorExpression(palette: PoiPalette): ExpressionSpecification {
  return buildCategoryColorExpression(palette.categories, palette.unknown)
}

/**
 * The single category-styled circle layer. One layer, one source — never per-POI markers.
 * The palette defaults to the document's, which is the only thing the composable ever passes;
 * taking it as an argument is what keeps this function pure and testable without a stylesheet.
 */
export function nearbyPoiLayer(palette: PoiPalette = readPoiPalette()): CircleLayerSpecification {
  return {
    id: POI_LAYER_ID,
    type: 'circle',
    source: POI_SOURCE_ID,
    paint: {
      'circle-radius': 6,
      'circle-color': categoryColorExpression(palette),
      'circle-stroke-width': 2,
      'circle-stroke-color': palette.stroke,
    },
  }
}

export function itemsToFeatureCollection(items: NearbyItem[]): FeatureCollection<Point> {
  return {
    type: 'FeatureCollection',
    features: items.map(
      (item): Feature<Point> => ({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [item.lon, item.lat] },
        properties: { category: item.category },
      }),
    ),
  }
}
