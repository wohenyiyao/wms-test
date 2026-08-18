import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { buildInventoryQuery, debounce } from '../filters'

describe('buildInventoryQuery（库存列表筛选参数组装）', () => {
  it('空白 keyword 应被剔除（不传 keyword 参数）', () => {
    const q = buildInventoryQuery('   ', undefined, false, 1, 20)
    expect(q.keyword).toBeUndefined()
    expect(q.lowStockOnly).toBe(false)
    expect(q.page).toBe(1)
    expect(q.pageSize).toBe(20)
  })

  it('keyword 应 trim 后传入', () => {
    const q = buildInventoryQuery('  蓝牙  ', undefined, false, 1, 20)
    expect(q.keyword).toBe('蓝牙')
  })

  it('warehouseId 未选（undefined）时不传该参数；选中后传入', () => {
    const without = buildInventoryQuery('', undefined, false, 1, 20)
    expect(without.warehouseId).toBeUndefined()
    const withWh = buildInventoryQuery('', 2, false, 1, 20)
    expect(withWh.warehouseId).toBe(2)
  })

  it('lowStockOnly 与分页参数原样透传', () => {
    const q = buildInventoryQuery('sku', 1, true, 3, 50)
    expect(q).toEqual({ keyword: 'sku', warehouseId: 1, lowStockOnly: true, page: 3, pageSize: 50 })
  })
})

describe('debounce（搜索防抖 300ms）', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('连续触发只执行最后一次（间隔小于 ms 时）', () => {
    const fn = vi.fn()
    const { run } = debounce(fn, 300)
    run()
    run()
    run()
    expect(fn).not.toHaveBeenCalled()
    vi.advanceTimersByTime(299)
    expect(fn).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('超过 ms 后再次触发会再次执行（非一次性）', () => {
    const fn = vi.fn()
    const { run } = debounce(fn, 300)
    run()
    vi.advanceTimersByTime(300)
    run()
    vi.advanceTimersByTime(300)
    expect(fn).toHaveBeenCalledTimes(2)
  })

  it('cancel 取消未触发的执行', () => {
    const fn = vi.fn()
    const { run, cancel } = debounce(fn, 300)
    run()
    cancel()
    vi.advanceTimersByTime(1000)
    expect(fn).not.toHaveBeenCalled()
  })
})
