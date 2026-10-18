import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import {
  Color,
  createPropertyExpression,
  v8,
  type StylePropertySpecification,
} from '@maplibre/maplibre-gl-style-spec'
import { CATEGORY_SLUGS, type CategorySlug } from '@/composables/useCategorySelection'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { resolve } from 'node:path'
import {
  HIT_TEST_PADDING_PX,
  POI_COLOR_PROPERTIES,
  STROKE_COLOR_PROPERTY,
  UNKNOWN_CATEGORY_PROPERTY,
  buildCategoryColorExpression,
  categoryColorExpression,
  hitTestBox,
  itemsToFeatureCollection,
  lockStateExpression,
  nearbyPoiLayer,
  pickPoiHit,
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

/** A feature as far as the paint is concerned. */
interface PaintedDot {
  category: string
  unlocked: boolean
}

/**
 * Evaluates the two expression forms this layer's paint is made of, so the colours asserted below
 * are the ones MapLibre would resolve for a given feature — not a re-derivation of what the paint
 * is assumed to say. Without this, "the inversion is measured" would really mean "the inversion is
 * measured somewhere else, and this test agrees with that copy".
 */
function resolvePaint(value: unknown, dot: PaintedDot): string {
  if (typeof value === 'string') return value

  const expr = value as unknown[]
  if (expr[0] === 'case') {
    expect(expr).toHaveLength(4)
    expect(expr[1]).toEqual(['==', ['get', 'unlocked'], true])
    return resolvePaint(dot.unlocked ? expr[2] : expr[3], dot)
  }
  if (expr[0] === 'match') {
    expect(expr[1]).toEqual(['get', 'category'])
    const pairs = expr.slice(2, expr.length - 1)
    for (let i = 0; i < pairs.length; i += 2) {
      if (pairs[i] === dot.category) return resolvePaint(pairs[i + 1], dot)
    }
    return resolvePaint(expr[expr.length - 1], dot)
  }
  throw new Error(`resolvePaint: unsupported expression ${JSON.stringify(expr[0])}`)
}

/**
 * Compiles one paint value with MapLibre's OWN expression compiler and hands back an evaluator for
 * it. Where `resolvePaint` walks the expression we wrote, this runs the expression the renderer
 * would run — so it also fails when the compiler REJECTS the paint, which no assertion about the
 * expression's shape can see.
 */
function paintEvaluator(
  layer: ReturnType<typeof nearbyPoiLayer>,
  key: 'circle-color' | 'circle-stroke-color',
): (properties: Record<string, unknown>) => Color {
  const compiled = createPropertyExpression(
    layer.paint?.[key],
    v8.paint_circle[key] as StylePropertySpecification,
  )
  if (compiled.result !== 'success') {
    throw new Error(`paintEvaluator: ${key} did not compile: ${JSON.stringify(compiled.value)}`)
  }
  return (properties) => compiled.value.evaluate({ zoom: 15 }, { type: 'Point', properties }) as Color
}

/**
 * The paint above is compiled by the style-spec package, and the value of that measurement is that
 * it is the SAME compiler maplibre-gl bundles at runtime. We now declare that package ourselves
 * (pinned, because a caret here could drift away from maplibre-gl's own range), so this guard is
 * what makes the drift loud: if a future maplibre-gl ever wants a range this pinned copy does not
 * satisfy, npm resolves a second, nested copy for it, this spec keeps compiling with the top-level
 * one, and every assertion here would quietly stop describing what ships. A version check cannot
 * be forgotten the way a comment can.
 */
describe('the style-spec these measurements compile with', () => {
  const require_ = createRequire(import.meta.url)
  const versionOf = (name: string): string =>
    (JSON.parse(readFileSync(require_.resolve(`${name}/package.json`), 'utf8')) as { version: string }).version

  it('is the one maplibre-gl itself asks for', () => {
    const required = (
      JSON.parse(readFileSync(require_.resolve('maplibre-gl/package.json'), 'utf8')) as {
        dependencies: Record<string, string>
      }
    ).dependencies['@maplibre/maplibre-gl-style-spec']
    const caret = /^\^(\d+)\.(\d+)\.(\d+)$/.exec(required)
    expect(caret, `unhandled range shape: ${required}`).not.toBeNull()

    const [major, minor, patch] = versionOf('@maplibre/maplibre-gl-style-spec').split('.').map(Number)
    const [reqMajor, reqMinor, reqPatch] = caret!.slice(1).map(Number)

    // Caret semantics, spelled out: same major, and at least the required minor.patch.
    expect(major).toBe(reqMajor)
    const atLeastRequired = minor > reqMinor || (minor === reqMinor && patch >= reqPatch)
    expect(atLeastRequired, `installed ${major}.${minor}.${patch} does not satisfy ${required}`).toBe(true)
  })
})

/** What a dot in the given state is actually painted with: its fill and its ring. */
function dotColors(
  layer: ReturnType<typeof nearbyPoiLayer>,
  dot: PaintedDot,
): { fill: string; ring: string } {
  return {
    fill: resolvePaint(layer.paint?.['circle-color'], dot),
    ring: resolvePaint(layer.paint?.['circle-stroke-color'], dot),
  }
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
  //
  // Measured in BOTH lock states, and read back out of the real paint: `viewpoints` clears the
  // floor by 0.09 (3.09:1 against white), so an inversion that quietly introduced a THIRD colour
  // would have nothing to spare. The result is not a coincidence and the test says why: the
  // inversion swaps the same two colours, the measured pair is the same unordered pair, and
  // contrastRatio is symmetric — so one measurement covers both states, which is asserted here
  // rather than assumed.
  it('clears 3:1 against its own ring, every category, unlocked AND locked', () => {
    const layer = nearbyPoiLayer(palette)

    for (const slug of [...CATEGORY_SLUGS, 'not-a-slug']) {
      const expectedCategoryColor =
        slug === 'not-a-slug' ? palette.unknown : palette.categories[slug as CategorySlug]

      const unlocked = dotColors(layer, { category: slug, unlocked: true })
      const locked = dotColors(layer, { category: slug, unlocked: false })

      // Same pair, inverted: fill and ring trade places and nothing else enters.
      expect(unlocked).toEqual({ fill: expectedCategoryColor, ring: palette.stroke })
      expect(locked).toEqual({ fill: palette.stroke, ring: expectedCategoryColor })

      const unlockedRatio = contrastRatio(unlocked.fill, unlocked.ring)
      const lockedRatio = contrastRatio(locked.fill, locked.ring)
      expect(unlockedRatio).toBeGreaterThanOrEqual(3)
      expect(lockedRatio).toBeGreaterThanOrEqual(3)
      expect(lockedRatio).toBe(unlockedRatio)
    }
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
    expect(layer.paint?.['circle-color']).toEqual(
      lockStateExpression(categoryColorExpression(fakePalette), fakePalette.stroke),
    )
    expect(layer.paint?.['circle-stroke-color']).toEqual(
      lockStateExpression(fakePalette.stroke, categoryColorExpression(fakePalette)),
    )
  })

  // The lock state is carried by the fill inversion ALONE. Shape, radius and icon are reserved for
  // the category (M6 picks one of them to solve colour-blind distinguishability), so a radius that
  // varied by lock state would quietly spend that budget here. A plain number, not an expression,
  // is what makes that unspendable.
  it('paints both lock states at one radius and one stroke width', () => {
    const layer = nearbyPoiLayer(fakePalette)
    expect(layer.paint?.['circle-radius']).toBe(6)
    expect(layer.paint?.['circle-stroke-width']).toBe(2)
  })

  it('inverts fill and ring between the two states, and nothing else', () => {
    const layer = nearbyPoiLayer(fakePalette)
    const unlocked = dotColors(layer, { category: 'museums', unlocked: true })
    const locked = dotColors(layer, { category: 'museums', unlocked: false })

    expect(unlocked).toEqual({ fill: fakePalette.categories.museums, ring: fakePalette.stroke })
    expect(locked).toEqual({ fill: fakePalette.stroke, ring: fakePalette.categories.museums })
  })

  /**
   * Anything that is not a literal `true` reads as LOCKED, and the paint still COMPILES — measured
   * through MapLibre's own expression compiler rather than asserted on the shape of the expression,
   * which would only restate the source. `['get', 'unlocked']` on its own returns null for a
   * feature that lacks the property, and `case` rejects a null predicate as a type error that
   * MapLibre resolves to the paint default: a black dot, not a locked one.
   *
   * These inputs are all reachable. A feature can be missing the property because an older payload
   * is still in the GeoJSON source — and a `queryRenderedFeatures` hit never carries a nullish
   * property at all, because the source round-trips through vt-pbf, which drops them (see
   * PoiFeatureProperties). `"true"` is the string a future hand-built feature or a tile-encoded
   * detour could arrive with.
   */
  it('reads every non-true `unlocked` as locked, out of a paint that compiles', () => {
    const layer = nearbyPoiLayer(fakePalette)
    const fill = paintEvaluator(layer, 'circle-color')
    const ring = paintEvaluator(layer, 'circle-stroke-color')
    const category = Color.parse(fakePalette.categories.museums)
    const stroke = Color.parse(fakePalette.stroke)

    // The anchor: the two states ARE told apart, so the agreement below is not vacuous.
    expect(fill({ category: 'museums', unlocked: true })).toEqual(category)
    expect(ring({ category: 'museums', unlocked: true })).toEqual(stroke)

    const notUnlocked = [
      { category: 'museums' },
      { category: 'museums', unlocked: null },
      { category: 'museums', unlocked: false },
      { category: 'museums', unlocked: 'true' },
    ]
    for (const properties of notUnlocked) {
      expect(fill(properties)).toEqual(stroke)
      expect(ring(properties)).toEqual(category)
    }
  })

  // The composable calls this with no argument, so the default is the production path: a layer
  // built from the document's tokens, not from anything hard-coded here.
  it('falls back to the document palette, so a token change reaches the map', () => {
    document.documentElement.style.setProperty('--app-poi-museums', '#123456')
    document.documentElement.style.setProperty('--app-poi-stroke', '#654321')

    const layer = nearbyPoiLayer()
    expect(dotColors(layer, { category: 'museums', unlocked: true })).toEqual({
      fill: '#123456',
      ring: '#654321',
    })
    expect(dotColors(layer, { category: 'museums', unlocked: false })).toEqual({
      fill: '#654321',
      ring: '#123456',
    })
  })
})

describe('itemsToFeatureCollection', () => {
  const items: NearbyItem[] = [
    { id: 'a', name: 'Rynek', category: 'attractions', lat: 51.11, lon: 17.03, distanceMeters: 5, unlocked: false },
    { id: 'b', name: null, category: 'museums', lat: 51.12, lon: 17.04, distanceMeters: 9, unlocked: true },
  ]

  // Each property is here for a reason and `toEqual` on the whole object is what pins them:
  // `category` and `unlocked` are what the paint keys on, and `id` is what a hit-test resolves back
  // to a NearbyItem. `name` and `distanceMeters` complete the payload the milestone specifies, but
  // the detail sheet (M3-09) reads them off that NearbyItem — a hit cannot be relied on to carry
  // them at all, see PoiFeatureProperties.
  it('emits GeoJSON points with [lon, lat] order and every property the map needs', () => {
    const fc = itemsToFeatureCollection(items)

    expect(fc.type).toBe('FeatureCollection')
    expect(fc.features).toHaveLength(2)

    const [first, second] = fc.features
    expect(first.geometry).toEqual({ type: 'Point', coordinates: [17.03, 51.11] })
    expect(first.properties).toEqual({
      id: 'a',
      name: 'Rynek',
      category: 'attractions',
      distanceMeters: 5,
      unlocked: false,
    })
    // An unnamed POI keeps a null name rather than being dropped or renamed, and `unlocked` is
    // carried per feature straight off the server's answer.
    expect(second.properties).toEqual({
      id: 'b',
      name: null,
      category: 'museums',
      distanceMeters: 9,
      unlocked: true,
    })
  })

  it('produces an empty collection for no items', () => {
    expect(itemsToFeatureCollection([])).toEqual({ type: 'FeatureCollection', features: [] })
  })
})

describe('hitTestBox', () => {
  // 22, not 20 or 24: the box is +/- half the frozen 44 px touch target, and the dot's own 12 px
  // of it is only a quarter of that floor.
  it('is the frozen 44 px target expressed as a box around the tap point', () => {
    expect(HIT_TEST_PADDING_PX).toBe(22)
    expect(hitTestBox({ x: 100, y: 200 })).toEqual([
      [78, 178],
      [122, 222],
    ])
  })

  it('stays a box when the tap is at the top-left corner of the canvas', () => {
    expect(hitTestBox({ x: 0, y: 0 })).toEqual([
      [-22, -22],
      [22, 22],
    ])
  })
})

describe('pickPoiHit', () => {
  it('picks nothing out of an empty box', () => {
    expect(pickPoiHit([])).toBeNull()
  })

  it('picks the POI nearest the tap point, not the first one the renderer returned', () => {
    expect(
      pickPoiHit([
        { osmId: 'node/far', distancePx: 21 },
        { osmId: 'node/near', distancePx: 3 },
        { osmId: 'node/mid', distancePx: 12 },
      ]),
    ).toBe('node/near')
  })

  // The reason the tie-break exists: `queryRenderedFeatures` returns features in render order, so
  // two dots at the same screen position would otherwise open a different sheet on each tap. Both
  // orders must land on the same POI.
  it('resolves an exact tie by id, whatever order the features arrive in', () => {
    const a = { osmId: 'node/1', distancePx: 8 }
    const b = { osmId: 'node/2', distancePx: 8 }
    expect(pickPoiHit([a, b])).toBe('node/1')
    expect(pickPoiHit([b, a])).toBe('node/1')
  })
})
