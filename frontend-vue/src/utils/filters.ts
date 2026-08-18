/**
 * 库存筛选相关纯逻辑（选做 B：前端单元测试对象）
 *
 * 从 InventoryView 中抽取，便于单测：
 * - buildInventoryQuery：把表单状态组装为库存查询参数（空值剔除、keyword trim）
 * - debounce：通用防抖（停止触发 ms 毫秒后才执行，支持手动取消）
 */

export interface InventoryQuery {
  keyword?: string
  warehouseId?: number
  lowStockOnly: boolean
  page: number
  pageSize: number
}

/** 组装库存查询参数：空白 keyword 剔除、warehouseId 未选不传、分页必填 */
export function buildInventoryQuery(
  keyword: string,
  warehouseId: number | undefined,
  lowStockOnly: boolean,
  page: number,
  pageSize: number
): InventoryQuery {
  const query: InventoryQuery = { lowStockOnly, page, pageSize }
  const trimmed = keyword.trim()
  if (trimmed) query.keyword = trimmed
  if (warehouseId !== undefined) query.warehouseId = warehouseId
  return query
}

/** 防抖包装：返回 run（触发防抖执行）与 cancel（取消未触发的执行） */
export function debounce<A extends unknown[]>(
  fn: (...args: A) => void,
  ms: number
): { run: (...args: A) => void; cancel: () => void } {
  let timer: ReturnType<typeof setTimeout> | undefined
  return {
    run(...args: A) {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        timer = undefined
        fn(...args)
      }, ms)
    },
    cancel() {
      if (timer) {
        clearTimeout(timer)
        timer = undefined
      }
    },
  }
}
