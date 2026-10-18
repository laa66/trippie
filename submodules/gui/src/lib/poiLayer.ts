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
 * Reads the lock state off a feature. `['get', 'unlocked']` alone would be enough for a feature
 * that has the property, but a missing one evaluates to `null`, and `case` wants a boolean — the
 * explicit comparison makes the absent case read as LOCKED instead of as an expression error that
 * MapLibre resolves to the paint default.
 */
const UNLOCKED_PREDICATE: ExpressionSpecification = ['==', ['get', 'unlocked'], true]

/**
 * Picks between an unlocked and a locked paint value. The lock state is carried by INVERTING the
 * fill, never by hue and never by shape: unlocked is the category colour inside a white ring,
 * locked is the same two colours the other way round. Shape, radius and icon stay entirely
 * reserved for the CATEGORY, so M6 can solve colour-blind distinguishability with whichever of
 * them it wants without touching this state.
 */
export function lockStateExpression(
  whenUnlocked: ExpressionSpecification | string,
  whenLocked: ExpressionSpecification | string,
): ExpressionSpecification {
  return ['case', UNLOCKED_PREDICATE, whenUnlocked, whenLocked]
}

/**
 * The single category-styled circle layer. One layer, one source — never per-POI markers.
 * The palette defaults to the document's, which is the only thing the composable ever passes;
 * taking it as an argument is what keeps this function pure and testable without a stylesheet.
 *
 * Both states are painted from the SAME pair of colours (the category's and the ring token), so
 * the contrast between a dot and its own ring is the same number in both — see the measurement in
 * poiLayer.spec.ts, which reads it back out of this paint rather than assuming the inversion.
 */
export function nearbyPoiLayer(palette: PoiPalette = readPoiPalette()): CircleLayerSpecification {
  const category = categoryColorExpression(palette)
  return {
    id: POI_LAYER_ID,
    type: 'circle',
    source: POI_SOURCE_ID,
    paint: {
      // One radius for both states, deliberately: see `lockStateExpression`.
      'circle-radius': 6,
      'circle-color': lockStateExpression(category, palette.stroke),
      'circle-stroke-width': 2,
      'circle-stroke-color': lockStateExpression(palette.stroke, category),
    },
  }
}

/**
 * The feature properties the layer paints from.
 *
 * Only `id` may be read BACK off a hit. A GeoJSON source round-trips its features through vector
 * tile encoding, and `@maplibre/vt-pbf` drops every property that is `== null` — so an unnamed POI
 * arrives from `queryRenderedFeatures` with no `name` key at all, not with `name: null` (a `false`
 * boolean survives intact, it is only nullish values that disappear). Everything a tap needs beyond
 * the id is therefore looked up on the `NearbyItem` by id, which is also the only copy that is
 * guaranteed to be as fresh as the last `nearby` answer.
 */
export interface PoiFeatureProperties {
  /** `osm_id`, the public POI id — what a hit resolves back to a NearbyItem by, and the only
   *  property a hit-test may rely on finding. */
  id: string
  name: string | null
  category: string
  /** As the server measured it, from the nearby query centre. Carried for the detail sheet. */
  distanceMeters: number
  /** Server truth from `nearby`, the only thing the lock state is painted from. */
  unlocked: boolean
}

export function itemsToFeatureCollection(items: NearbyItem[]): FeatureCollection<Point> {
  return {
    type: 'FeatureCollection',
    features: items.map(
      (item): Feature<Point> => ({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [item.lon, item.lat] },
        properties: {
          id: item.id,
          name: item.name,
          category: item.category,
          distanceMeters: item.distanceMeters,
          unlocked: item.unlocked,
        } satisfies PoiFeatureProperties,
      }),
    ),
  }
}

/**
 * Half the frozen 44 px touch target, as a padding around the tap point. A 6 px circle is a 12 px
 * target, which is less than a third of the floor — but growing the dot to meet it would make the
 * map unreadable in a cluster, and a second transparent layer just for hit-testing would double
 * the features MapLibre renders. Querying a BOX instead costs nothing at render time and leaves
 * the dot exactly as designed.
 */
export const HIT_TEST_PADDING_PX = 22

export interface ScreenPoint {
  x: number
  y: number
}

/** The ±22 px box `queryRenderedFeatures` is asked for, around the tap point. */
export function hitTestBox(point: ScreenPoint): [[number, number], [number, number]] {
  return [
    [point.x - HIT_TEST_PADDING_PX, point.y - HIT_TEST_PADDING_PX],
    [point.x + HIT_TEST_PADDING_PX, point.y + HIT_TEST_PADDING_PX],
  ]
}

export interface PoiHit {
  osmId: string
  /** Pixels between the tap point and this POI's rendered centre. */
  distancePx: number
}

/**
 * Picks ONE POI out of everything the box caught. A 44 px box over the old town routinely holds
 * several dots, and `queryRenderedFeatures` returns them in render order — which depends on tile
 * load order and z-order, i.e. the same tap could open a different POI twice in a row.
 *
 * So the rule is nearest rendered centre to the tap point, with the osm_id as the tie-break. The
 * first half is the user's intent (the dot they aimed at); the second is what makes two POIs at
 * the same screen position — duplicate OSM nodes, or two dots a metre apart at z15 — resolve to
 * the same one every time instead of by arrival order.
 */
export function pickPoiHit(hits: readonly PoiHit[]): string | null {
  let best: PoiHit | null = null
  for (const hit of hits) {
    if (
      best === null ||
      hit.distancePx < best.distancePx ||
      (hit.distancePx === best.distancePx && hit.osmId < best.osmId)
    ) {
      best = hit
    }
  }
  return best === null ? null : best.osmId
}
