import { flushPromises, type VueWrapper } from '@vue/test-utils'

export async function fillInputs(wrapper: VueWrapper, values: string[]): Promise<void> {
  const inputs = wrapper.findAll('input:not([type=checkbox]):not([type=radio])')
  for (const [i, value] of values.entries()) await inputs[i].setValue(value)
}

export async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const buttons = wrapper.findAll('button').filter((b) => b.text().includes(text))
  if (buttons.length !== 1) throw new Error(`expected exactly 1 button matching "${text}", got ${buttons.length}`)
  await buttons[0].trigger('click')
  await flushPromises()
}
