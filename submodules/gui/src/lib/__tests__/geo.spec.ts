import { describe, expect, it } from 'vitest'
import {
  haversineMeters,
  viewportRadiusMeters,
  MAX_RADIUS_METERS,
} from '@/lib/geo'

describe('haversineMeters', () => {
  it('is zero for identical points', () => {
    expect(haversineMeters({ lat: 51, lon: 17 }, { lat: 51, lon: 17 })).toBe(0)
  })

  it('matches a known ~1.1 km north–south span within tolerance', () => {
    // 0.01° of latitude ≈ 1113 m anywhere on Earth.
    const d = haversineMeters({ lat: 51, lon: 17 }, { lat: 51.01, lon: 17 })
    expect(d).toBeGreaterThan(1108)
    expect(d).toBeLessThan(1118)
  })

  it('is symmetric', () => {
    const a = { lat: 51.1079, lon: 17.0385 }
    const b = { lat: 51.12, lon: 17.05 }
    expect(haversineMeters(a, b)).toBeCloseTo(haversineMeters(b, a), 6)
  })
})

describe('viewportRadiusMeters', () => {
  it('returns the rounded centre→corner distance for a small viewport', () => {
    const center = { lat: 51, lon: 17 }
    const corner = { lat: 51.005, lon: 17 }
    expect(viewportRadiusMeters(center, corner)).toBe(
      Math.round(haversineMeters(center, corner)),
    )
    expect(viewportRadiusMeters(center, corner)).toBeLessThan(MAX_RADIUS_METERS)
  })

  it('caps a wide viewport at MAX_RADIUS_METERS', () => {
    expect(viewportRadiusMeters({ lat: 51, lon: 17 }, { lat: 52, lon: 17 })).toBe(
      MAX_RADIUS_METERS,
    )
  })
})
