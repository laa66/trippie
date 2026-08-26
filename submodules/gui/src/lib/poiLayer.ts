import type { ExpressionSpecification, CircleLayerSpecification } from '@maplibre/maplibre-gl-style-spec'
import type { Feature, FeatureCollection, Point } from 'geojson'
import { CATEGORY_SLUGS, type CategorySlug } from '@/composables/useCategorySelection'
import type { NearbyItem } from '@/lib/nearbyApi'

export const POI_SOURCE_ID = 'nearby-pois'
export const POI_LAYER_ID = 'nearby-pois-circles'

/**
 * Per-category circle colour: distinct hues spread across the wheel so the 8
 * categories stay separable on the basemap. Keyed by the frozen slug contract,
 * so the compiler already forces every slug present; the builder's runtime guard
 * turns a future drift (a slug with no colour) into a loud failure.
 */
export const CATEGORY_COLORS: Record<CategorySlug, string> = {
  public_art: '#dc2626',
  monuments: '#ea580c',
  heritage: '#ca8a04',
  sacred: '#7c3aed',
  museums: '#2563eb',
  viewpoints: '#16a34a',
  architecture: '#0891b2',
  attractions: '#db2777',
}

/** Fallback for a feature whose category is not one of the 8 slugs — the DB CHECK makes this unreachable. */
export const UNKNOWN_CATEGORY_COLOR = '#6b7280'

/**
 * A MapLibre `match` expression on the `category` feature property. Built by
 * iterating the slug contract, so it covers every slug by construction and
 * throws loudly if a slug is missing a colour.
 */
export function buildCategoryColorExpression(
  colors: Partial<Record<CategorySlug, string>>,
  fallback: string = UNKNOWN_CATEGORY_COLOR,
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

export function categoryColorExpression(): ExpressionSpecification {
  return buildCategoryColorExpression(CATEGORY_COLORS)
}

/** The single category-styled circle layer. One layer, one source — never per-POI markers. */
export function nearbyPoiLayer(): CircleLayerSpecification {
  return {
    id: POI_LAYER_ID,
    type: 'circle',
    source: POI_SOURCE_ID,
    paint: {
      'circle-radius': 6,
      'circle-color': categoryColorExpression(),
      'circle-stroke-width': 2,
      'circle-stroke-color': '#ffffff',
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
