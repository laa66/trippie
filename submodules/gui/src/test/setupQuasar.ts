import { config } from '@vue/test-utils'
import { Quasar } from 'quasar'

config.global.plugins = [[Quasar, {}]]

// jsdom has no ResizeObserver and QLayout throws without one. Plain assignment, not vi.stubGlobal:
// specs call vi.unstubAllGlobals() and that would take a stubbed one away mid-file.
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}
