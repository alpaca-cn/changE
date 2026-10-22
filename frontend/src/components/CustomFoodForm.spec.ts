import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
const invokeMock = vi.mocked(invoke)

import CustomFoodForm from './CustomFoodForm.vue'

async function mountForm() {
  const w = mount(CustomFoodForm)
  await flushPromises()
  return w
}

/** 给某个带 label 的输入框填值。 */
async function fill(w: Awaited<ReturnType<typeof mountForm>>, label: string, value: string) {
  const field = w.findAll('.f').find((f) => f.find('span').text() === label)
  expect(field, `找不到字段：${label}`).toBeDefined()
  await field!.find('input').setValue(value)
}

describe('新建食物 · 换算口径', () => {
  it('每份值换算成每100克，并实时显示', async () => {
    const w = await mountForm()
    await fill(w, '名字', '测试黄焖鸡')
    await fill(w, '这一份多少克', '400')
    await fill(w, '蛋白 g', '28.8')
    await fill(w, '脂肪 g', '12')
    await fill(w, '碳水 g', '40')

    // 28.8/400*100 = 7.2 ；12/400*100 = 3 ；40/400*100 = 10
    const preview = w.find('.preview').text()
    expect(preview).toContain('7.2')
    expect(preview).toContain('蛋白')
    expect(preview).toContain('每 100 克')
  })

  it('没填热量时按 4/4/9 算这一份的热量', async () => {
    const w = await mountForm()
    await fill(w, '这一份多少克', '100')
    await fill(w, '蛋白 g', '10')
    await fill(w, '脂肪 g', '5')
    await fill(w, '碳水 g', '20')

    // 4*10 + 4*20 + 9*5 = 165
    expect(w.find('.preview').text()).toContain('165')
  })

  it('填了热量就用填的，不覆盖', async () => {
    const w = await mountForm()
    await fill(w, '这一份多少克', '100')
    await fill(w, '蛋白 g', '10')
    await fill(w, '脂肪 g', '5')
    await fill(w, '热量 kcal（可不填）', '200')

    // 若被 4/4/9 覆盖会是 85
    const text = w.find('.preview').text()
    expect(text).toContain('200')
    expect(text).not.toContain('85')
  })

  it('份量没填时不显示换算结果，也不给保存', async () => {
    const w = await mountForm()
    await fill(w, '名字', '测试没份量')
    await fill(w, '蛋白 g', '10')

    expect(w.find('.preview').text()).toContain('填了')
    expect(w.find('.save').attributes('disabled')).toBeDefined()
  })

  it('不填任何营养值时不给保存', async () => {
    const w = await mountForm()
    await fill(w, '名字', '测试空营养')
    await fill(w, '这一份多少克', '100')
    expect(w.find('.save').attributes('disabled')).toBeDefined()
  })

  it('保存时提交的是每份值（换算交给后端）', async () => {
    invokeMock.mockReset()
    invokeMock.mockImplementation(async () => ({
      status: 200,
      body: JSON.stringify({
        id: 999, nameZh: '测试提交', nameEn: '测试提交', source: 'manual',
        per100: { kcal: 7.2, protein: 7.2, carb: 10, fat: 3 }, portions: [],
      }),
    }))

    const w = await mountForm()
    await fill(w, '名字', '测试提交')
    await fill(w, '这一份多少克', '400')
    await fill(w, '蛋白 g', '28.8')
    await w.find('.save').trigger('click')
    await flushPromises()

    const args = invokeMock.mock.calls[0][1] as { method: string; path: string; body: string }
    expect(args.method).toBe('POST')
    expect(args.path).toBe('/api/foods')
    const sent = JSON.parse(args.body)
    // **前端送的是每份值 28.8，不是每 100 克的 7.2** ——
    // 换算只能有一份实现，放在后端；两处各算一次迟早会不一致。
    expect(sent.protein).toBe(28.8)
    expect(sent.grams).toBe(400)
  })

  it('保存成功后清空表单并通知上层', async () => {
    invokeMock.mockReset()
    invokeMock.mockImplementation(async () => ({
      status: 200,
      body: JSON.stringify({
        id: 1, nameZh: 'X', nameEn: 'X', source: 'manual',
        per100: { kcal: 1, protein: 1, carb: 1, fat: 1 }, portions: [],
      }),
    }))
    const w = await mountForm()
    await fill(w, '名字', 'X')
    await fill(w, '这一份多少克', '100')
    await fill(w, '蛋白 g', '5')
    await w.find('.save').trigger('click')
    await flushPromises()

    expect(w.emitted('created')).toBeTruthy()
    expect((w.find('.f input').element as HTMLInputElement).value).toBe('')
  })

  it('后端拒绝时把中文原因显示出来', async () => {
    invokeMock.mockReset()
    invokeMock.mockImplementation(async () => ({
      status: 400,
      body: JSON.stringify({ error: '食物库里已经有「X」了，直接搜它就行。' }),
    }))
    const w = await mountForm()
    await fill(w, '名字', 'X')
    await fill(w, '这一份多少克', '100')
    await fill(w, '蛋白 g', '5')
    await w.find('.save').trigger('click')
    await flushPromises()

    expect(w.find('.err').text()).toContain('已经有')
  })
})
