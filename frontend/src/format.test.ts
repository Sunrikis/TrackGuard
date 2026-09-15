import { describe, expect, it } from 'vitest'
import { validRegion } from './format'
describe('manual track calibration', () => {
  it('accepts ordered straight and curved polygons in either direction', () => {
    expect(
      validRegion([
        [0.1, 0.1],
        [0.9, 0.1],
        [0.9, 0.9],
        [0.1, 0.9],
      ]),
    ).toBe(true)
    expect(
      validRegion([
        [0.4, 0.1],
        [0.32, 0.45],
        [0.2, 0.9],
        [0.8, 0.9],
        [0.7, 0.45],
        [0.55, 0.1],
      ]),
    ).toBe(true)
    expect(
      validRegion([
        [0.1, 0.9],
        [0.9, 0.9],
        [0.9, 0.1],
        [0.1, 0.1],
      ]),
    ).toBe(true)
  })
  it('rejects incomplete, crossed, tiny and nonfinite regions', () => {
    expect(validRegion([[0.1, 0.1]])).toBe(false)
    expect(
      validRegion([
        [0.1, 0.1],
        [0.9, 0.9],
        [0.1, 0.9],
        [0.9, 0.1],
      ]),
    ).toBe(false)
    expect(
      validRegion([
        [0.1, 0.1],
        [0.101, 0.1],
        [0.101, 0.101],
        [0.1, 0.101],
      ]),
    ).toBe(false)
    expect(
      validRegion([
        [NaN, 0.1],
        [0.9, 0.1],
        [0.9, 0.9],
        [0.1, 0.9],
      ]),
    ).toBe(false)
  })
})
