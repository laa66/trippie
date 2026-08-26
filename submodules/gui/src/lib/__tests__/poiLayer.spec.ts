import { describe, expect, it } from 'vitest'
import { CATEGORY_SLUGS } from '@/composables/useCategorySelection'
import {
  CATEGORY_COLORS,
  UNKNOWN_CATEGORY_COLOR,
  buildCategoryColorExpression,
  categoryColorExpression,
  itemsToFeatureCollection,
  nearbyPoiLayer,
  POI_LAYER_ID,
  POI_SOURCE_ID,
} from '@/lib/poiLayer'
import type { NearbyItem } from '@/lib/nearbyApi'

describe('CATEGORY_COLORS', () => {
  it('covers every one of the 8 slugs', () => {
    expect(Object.keys(CATEGORY_COLORS).sort()).toEqual([...CATEGORY_SLUGS].sort())
  })

  it('gives each category a distinct colour, none equal to the fallback', () => {
    const colors = Object.values(CATEGORY_COLORS)
    expect(new Set(colors).size).toBe(colors.length)
    expect(colors).not.toContain(UNKNOWN_CATEGORY_COLOR)
  })
})

describe('categoryColorExpression', () => {
  it('is a match on the category property with a branch per slug and a fallback', () => {
    const expr = categoryColorExpression()

    expect(expr[0]).toBe('match')
    expect(expr[1]).toEqual(['get', 'category'])
    // 2 head + 8 (slug,colour) pairs + 1 fallback
    expect(expr).toHaveLength(2 + CATEGORY_SLUGS.length * 2 + 1)
    expect(expr[expr.length - 1]).toBe(UNKNOWN_CATEGORY_COLOR)
  })

  it('maps every slug to its distinct colour', () => {
    const expr = categoryColorExpression()
    const pairs = expr.slice(2, expr.length - 1)

    const mapped: Record<string, unknown> = {}
    for (let i = 0; i < pairs.length; i += 2) {
      mapped[pairs[i] as string] = pairs[i + 1]
    }

    for (const slug of CATEGORY_SLUGS) {
      expect(mapped[slug]).toBe(CATEGORY_COLORS[slug])
    }
    const outputs = Object.values(mapped)
    expect(new Set(outputs).size).toBe(CATEGORY_SLUGS.length)
  })
})

describe('buildCategoryColorExpression', () => {
  it('throws loudly when a slug has no colour', () => {
    const { museums, ...missingMuseums } = CATEGORY_COLORS
    void museums
    expect(() => buildCategoryColorExpression(missingMuseums)).toThrow(/museums/)
  })

  it('honours a custom fallback', () => {
    const expr = buildCategoryColorExpression(CATEGORY_COLORS, '#000000')
    expect(expr[expr.length - 1]).toBe('#000000')
  })
})

describe('nearbyPoiLayer', () => {
  it('is a single circle layer bound to the POI source', () => {
    const layer = nearbyPoiLayer()
    expect(layer.id).toBe(POI_LAYER_ID)
    expect(layer.type).toBe('circle')
    expect(layer.source).toBe(POI_SOURCE_ID)
    expect(layer.paint?.['circle-color']).toEqual(categoryColorExpression())
  })
})

describe('itemsToFeatureCollection', () => {
  const items: NearbyItem[] = [
    { id: 'a', name: 'Rynek', category: 'attractions', lat: 51.11, lon: 17.03, distanceMeters: 5 },
    { id: 'b', name: null, category: 'museums', lat: 51.12, lon: 17.04, distanceMeters: 9 },
  ]

  it('emits GeoJSON points with [lon, lat] order and the category property', () => {
    const fc = itemsToFeatureCollection(items)

    expect(fc.type).toBe('FeatureCollection')
    expect(fc.features).toHaveLength(2)

    const [first] = fc.features
    expect(first.geometry).toEqual({ type: 'Point', coordinates: [17.03, 51.11] })
    expect(first.properties).toEqual({ category: 'attractions' })
  })

  it('produces an empty collection for no items', () => {
    expect(itemsToFeatureCollection([])).toEqual({ type: 'FeatureCollection', features: [] })
  })
})
