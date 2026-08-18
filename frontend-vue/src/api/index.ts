import api from './client'

// ============ 商品（参考实现） ============

export interface Product {
  id: number
  name: string
  sku: string
  unit: string
  createdAt: string
  updatedAt: string
}

export const getProducts = (keyword?: string) =>
  api.get<any, { code: number; data: Product[] }>('/products', { params: { keyword } })

export const getProduct = (id: number) =>
  api.get<any, { code: number; data: Product }>(`/products/${id}`)

export const createProduct = (data: { name: string; sku: string; unit?: string }) =>
  api.post('/products', data)

export const updateProduct = (id: number, data: { name: string; unit?: string }) =>
  api.put(`/products/${id}`, data)

export const deleteProduct = (id: number, force = false) =>
  api.delete(`/products/${id}`, { params: { force } })


// ============ 仓库 & 库位 ============

export interface Warehouse {
  id: number
  code: string
  name: string
}

export interface Location {
  id: number
  warehouseId: number
  code: string
  status: string
}

export const getWarehouses = () =>
  api.get<any, { code: number; data: Warehouse[] }>('/warehouses')

export const getLocations = (warehouseId: number) =>
  api.get<any, { code: number; data: Location[] }>(`/warehouses/${warehouseId}/locations`)


// ============ 库存查询（候选人实现） ============

export interface InventoryItem {
  inventoryId: number
  productId: number
  productName: string
  sku: string
  locationCode: string
  warehouseName: string
  quantity: number
  updatedAt: string
}

export const getInventory = (params: {
  keyword?: string
  warehouseId?: number
  /** 选做A：按商品精确过滤（出库页展示该商品各库位可用库存用） */
  productId?: number
  lowStockOnly?: boolean
  page?: number
  pageSize?: number
}) =>
  api.get<any, { code: number; data: { list: InventoryItem[]; total: number; page: number; pageSize: number } }>(
    '/inventory',
    { params }
  )


// ============ 入库单（候选人实现） ============

export interface InboundItemRequest {
  productId: number
  quantity: number
  locationCode: string
}

export const createInboundOrder = (data: {
  supplierName: string
  /** 幂等键：弱网重试时复用同一 requestId，后端保证不会重复创建入库单 */
  requestId?: string
  items: InboundItemRequest[]
}) =>
  api.post('/inbound-orders', data)


// ============ 出库单（选做A） ============

export interface OutboundItemRequest {
  productId: number
  quantity: number
  locationCode: string
}

export const createOutboundOrder = (data: {
  customerName: string
  /** 幂等键：弱网重试时复用同一 requestId，后端保证不会重复出库/重复扣库存 */
  requestId?: string
  items: OutboundItemRequest[]
}) =>
  api.post('/outbound-orders', data)
