import { IonInput } from '@ionic/vue'
import { flushPromises, type VueWrapper } from '@vue/test-utils'

export function fillInputs(wrapper: VueWrapper, values: string[]): void {
  const inputs = wrapper.findAllComponents(IonInput)
  values.forEach((value, i) => inputs[i].vm.$emit('ionInput', { detail: { value } }))
}

export async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('ion-button').find((b) => b.text().includes(text))
  if (!button) throw new Error(`button not found: ${text}`)
  await button.trigger('click')
  await flushPromises()
}
