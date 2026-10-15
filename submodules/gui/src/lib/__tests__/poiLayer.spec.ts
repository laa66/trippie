import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { CATEGORY_SLUGS } from '@/composables/useCategorySelection'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  POI_COLOR_PROPERTIES,
  STROKE_COLOR_PROPERTY,
  UNKNOWN_CATEGORY_PROPERTY,
  buildCategoryColorExpression,
  categoryColorExpression,
  itemsToFeatureCollection,
  nearbyPoiLayer,
  readPoiPalette,
  POI_LAYER_ID,
  POI_SOURCE_ID,
  type PoiPalette,
} from '@/lib/poiLayer'
import type { NearbyItem } from '@/lib/nearbyApi'

/**
 * The palette is not in this module any more: poiLayer.ts owns only the custom-property names and
 * the values live in theme.css, which src/test/setupQuasar.ts replays into the document. So these
 * assertions run against the real frozen tokens, resolved exactly the way the browser resolves
 * them — not against a copy kept in the test tree.
 */

function relativeLuminance(hex: string): number {
  const channels = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
}

function contrastRatio(a: string, b: string): number {
  const [hi, lo] = [relativeLuminance(a), relativeLuminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** sRGB hex -> CIE L*a*b* (D65), the space perceptual distance is defined in. */
function toLab(hex: string): [number, number, number] {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  const x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047
  const y = r * 0.2126729 + g * 0.7151522 + b * 0.072175
  const z = (r * 0.0193339 + g * 0.119192 + b * 0.9503041) / 1.08883
  const f = (t: number) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116)
  return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))]
}

/**
 * CIEDE2000. "Distinct" has to be a measured property, not a hex comparison: `#0c7e5b` is a
 * different hex from the brand green and indistinguishable from it on screen (dE 0.5), and a
 * palette built from equality alone would accept it.
 */
function deltaE2000(hexA: string, hexB: string): number {
  const rad = Math.PI / 180
  const [l1, a1, b1] = toLab(hexA)
  const [l2, a2, b2] = toLab(hexB)
  const c1 = Math.hypot(a1, b1)
  const c2 = Math.hypot(a2, b2)
  const cBar = (c1 + c2) / 2
  const g = 0.5 * (1 - Math.sqrt(cBar ** 7 / (cBar ** 7 + 25 ** 7)))
  const ap1 = (1 + g) * a1
  const ap2 = (1 + g) * a2
  const cp1 = Math.hypot(ap1, b1)
  const cp2 = Math.hypot(ap2, b2)
  const hp = (b: number, ap: number) => (b === 0 && ap === 0 ? 0 : ((Math.atan2(b, ap) / rad) + 360) % 360)
  const hp1 = hp(b1, ap1)
  const hp2 = hp(b2, ap2)

  const dL = l2 - l1
  const dC = cp2 - cp1
  let dh = 0
  if (cp1 * cp2 !== 0) {
    dh = hp2 - hp1
    if (dh > 180) dh -= 360
    else if (dh < -180) dh += 360
  }
  const dH = 2 * Math.sqrt(cp1 * cp2) * Math.sin((dh * rad) / 2)

  const lBar = (l1 + l2) / 2
  const cpBar = (cp1 + cp2) / 2
  let hBar = hp1 + hp2
  if (cp1 * cp2 !== 0) {
    if (Math.abs(hp1 - hp2) <= 180) hBar = (hp1 + hp2) / 2
    else hBar = hp1 + hp2 < 360 ? (hp1 + hp2 + 360) / 2 : (hp1 + hp2 - 360) / 2
  }
  const t =
    1 -
    0.17 * Math.cos((hBar - 30) * rad) +
    0.24 * Math.cos(2 * hBar * rad) +
    0.32 * Math.cos((3 * hBar + 6) * rad) -
    0.2 * Math.cos((4 * hBar - 63) * rad)
  const sL = 1 + (0.015 * (lBar - 50) ** 2) / Math.sqrt(20 + (lBar - 50) ** 2)
  const sC = 1 + 0.045 * cpBar
  const sH = 1 + 0.015 * cpBar * t
  const rT =
    -2 *
    Math.sqrt(cpBar ** 7 / (cpBar ** 7 + 25 ** 7)) *
    Math.sin(60 * Math.exp(-(((hBar - 275) / 25) ** 2)) * rad)

  return Math.sqrt((dL / sL) ** 2 + (dC / sC) ** 2 + (dH / sH) ** 2 + rT * (dC / sC) * (dH / sH))
}

const fakePalette: PoiPalette = {
  categories: Object.fromEntries(
    CATEGORY_SLUGS.map((slug, i) => [slug, `rgb(${i}, ${i}, ${i})`]),
  ) as PoiPalette['categories'],
  unknown: 'rgb(9, 9, 9)',
  stroke: 'rgb(8, 8, 8)',
}

afterEach(() => {
  document.documentElement.removeAttribute('style')
})

describe('readPoiPalette', () => {
  it('resolves one colour per slug, plus the fallback and the ring, off the document', () => {
    const palette = readPoiPalette()

    expect(Object.keys(palette.categories).sort()).toEqual([...CATEGORY_SLUGS].sort())
    for (const slug of CATEGORY_SLUGS) {
      expect(palette.categories[slug]).toMatch(/^#[0-9a-f]{6}$/i)
    }
    expect(palette.unknown).toMatch(/^#[0-9a-f]{6}$/i)
    expect(palette.stroke).toMatch(/^#[0-9a-f]{6}$/i)
  })

  it('names a property for every colour it needs', () => {
    expect(POI_COLOR_PROPERTIES).toHaveLength(CATEGORY_SLUGS.length + 2)
    expect(POI_COLOR_PROPERTIES).toContain(UNKNOWN_CATEGORY_PROPERTY)
    expect(POI_COLOR_PROPERTIES).toContain(STROKE_COLOR_PROPERTY)
    expect(new Set(POI_COLOR_PROPERTIES).size).toBe(POI_COLOR_PROPERTIES.length)
  })

  // An undefined property resolves to '', which MapLibre takes as a broken paint value and renders
  // as invisible POIs. Renaming a token in theme.css must fail at the layer, not on the map.
  it('throws, naming the property, when a token is missing', () => {
    const detached = document.createElement('div')
    document.body.append(detached)
    try {
      expect(() => readPoiPalette(detached)).toThrow(/--app-poi-/)
    } finally {
      detached.remove()
    }
  })
})

// Off disk, not imported: vitest stubs CSS/SCSS imports to an empty module, `?raw` included.
const quasarVariables = readFileSync(resolve(process.cwd(), 'src/css/quasar.variables.scss'), 'utf8')
const themeCss = readFileSync(resolve(process.cwd(), 'src/theme.css'), 'utf8')

describe('the frozen POI palette', () => {
  // Resolved in beforeAll, not in the describe body: a missing token makes readPoiPalette throw,
  // and thrown at collection time it takes the whole file down - including the specs below that
  // have nothing to do with colour.
  let palette: PoiPalette
  let colors: string[]
  let brand: string

  beforeAll(() => {
    palette = readPoiPalette()
    colors = Object.values(palette.categories)
    const primary = quasarVariables.match(/\$primary:\s*(#[0-9a-f]{6})/i)?.[1]
    expect(primary).toBeDefined()
    brand = primary!
  })

  // The setup file replays theme.css into jsdom with its own regex; if that regex and the
  // contract ever disagree on how many tokens there are, every colour assertion here silently
  // stops covering part of the palette.
  it('declares exactly the tokens poiLayer.ts asks for', () => {
    const declared = [...themeCss.matchAll(/--app-poi-[a-z0-9-]+\s*:/g)]
    expect(declared).toHaveLength(POI_COLOR_PROPERTIES.length)
  })

  it('gives each category a distinct colour, none equal to the fallback', () => {
    expect(new Set(colors).size).toBe(colors.length)
    expect(colors).not.toContain(palette.unknown)
  })

  // The user-location marker is painted in the brand green. A category sharing that hex would make
  // the one dot on the map that is NOT a POI indistinguishable from the POIs around it.
  // dE2000 >= 20, not "a different hex": the threshold is the one theme.css and /style both
  // state, and it is what the comment about the marker actually promises. The frozen palette's
  // worst offender is viewpoints at 23.9.
  it('keeps every category perceptually clear of the brand the user marker uses', () => {
    for (const slug of CATEGORY_SLUGS) {
      expect(deltaE2000(palette.categories[slug], brand)).toBeGreaterThanOrEqual(20)
    }
  })

  // Same idea one level across: the categories have to be told apart from each other, and 14.4 is
  // the worst pair in the frozen set.
  it('keeps the categories perceptually clear of each other', () => {
    for (const [i, a] of colors.entries()) {
      for (const b of colors.slice(i + 1)) {
        expect(deltaE2000(a, b)).toBeGreaterThanOrEqual(14)
      }
    }
  })

  // The ring is the only thing between a dot and the basemap, so a dot that does not contrast with
  // its own ring reads as a white blob. 3:1 is the WCAG floor for a graphical object.
  it('clears 3:1 against its own ring, every category', () => {
    for (const slug of CATEGORY_SLUGS) {
      expect(contrastRatio(palette.categories[slug], palette.stroke)).toBeGreaterThanOrEqual(3)
    }
    expect(contrastRatio(palette.unknown, palette.stroke)).toBeGreaterThanOrEqual(3)
  })
})

describe('categoryColorExpression', () => {
  it('is a match on the category property with a branch per slug and a fallback', () => {
    const expr = categoryColorExpression(fakePalette)

    expect(expr[0]).toBe('match')
    expect(expr[1]).toEqual(['get', 'category'])
    // 2 head + 8 (slug,colour) pairs + 1 fallback
    expect(expr).toHaveLength(2 + CATEGORY_SLUGS.length * 2 + 1)
    expect(expr[expr.length - 1]).toBe(fakePalette.unknown)
  })

  it('maps every slug to its own colour', () => {
    const expr = categoryColorExpression(fakePalette)
    const pairs = expr.slice(2, expr.length - 1)

    const mapped: Record<string, unknown> = {}
    for (let i = 0; i < pairs.length; i += 2) {
      mapped[pairs[i] as string] = pairs[i + 1]
    }

    for (const slug of CATEGORY_SLUGS) {
      expect(mapped[slug]).toBe(fakePalette.categories[slug])
    }
    expect(new Set(Object.values(mapped)).size).toBe(CATEGORY_SLUGS.length)
  })
})

describe('buildCategoryColorExpression', () => {
  it('throws loudly when a slug has no colour', () => {
    const { museums, ...missingMuseums } = fakePalette.categories
    void museums
    expect(() => buildCategoryColorExpression(missingMuseums, '#000000')).toThrow(/museums/)
  })

  it('honours the fallback it is given', () => {
    const expr = buildCategoryColorExpression(fakePalette.categories, '#000000')
    expect(expr[expr.length - 1]).toBe('#000000')
  })
})

describe('nearbyPoiLayer', () => {
  it('is a single circle layer bound to the POI source', () => {
    const layer = nearbyPoiLayer(fakePalette)
    expect(layer.id).toBe(POI_LAYER_ID)
    expect(layer.type).toBe('circle')
    expect(layer.source).toBe(POI_SOURCE_ID)
    expect(layer.paint?.['circle-color']).toEqual(categoryColorExpression(fakePalette))
    expect(layer.paint?.['circle-stroke-color']).toBe(fakePalette.stroke)
  })

  // The composable calls this with no argument, so the default is the production path: a layer
  // built from the document's tokens, not from anything hard-coded here.
  it('falls back to the document palette, so a token change reaches the map', () => {
    document.documentElement.style.setProperty('--app-poi-museums', '#123456')
    document.documentElement.style.setProperty('--app-poi-stroke', '#654321')

    const layer = nearbyPoiLayer()
    expect(layer.paint?.['circle-color']).toContain('#123456')
    expect(layer.paint?.['circle-stroke-color']).toBe('#654321')
  })
})

describe('itemsToFeatureCollection', () => {
  const items: NearbyItem[] = [
    { id: 'a', name: 'Rynek', category: 'attractions', lat: 51.11, lon: 17.03, distanceMeters: 5, unlocked: false },
    { id: 'b', name: null, category: 'museums', lat: 51.12, lon: 17.04, distanceMeters: 9, unlocked: true },
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
