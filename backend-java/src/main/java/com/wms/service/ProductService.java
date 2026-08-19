package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.dto.ProductCreateRequest;
import com.wms.dto.ProductResponse;
import com.wms.dto.ProductUpdateRequest;
import com.wms.entity.Product;
import com.wms.repository.InboundOrderItemRepository;
import com.wms.repository.InventoryRepository;
import com.wms.repository.OutboundOrderItemRepository;
import com.wms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品管理 Service — 参考实现
 * 展示了：参数校验、异常处理、事务管理、DTO 转换
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InboundOrderItemRepository inboundOrderItemRepository;
    private final OutboundOrderItemRepository outboundOrderItemRepository;

    public List<ProductResponse> list(String keyword) {
        List<Product> products = productRepository.search(keyword);
        return products.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ProductResponse getById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "商品不存在"));
        return toResponse(product);
    }

    @Transactional
    public ProductResponse create(ProductCreateRequest request) {
        if (productRepository.countBySkuAll(request.getSku()) > 0) {
            throw new BusinessException("SKU已存在: " + request.getSku());
        }
        Product product = Product.builder()
                .name(request.getName())
                .sku(request.getSku())
                .unit(request.getUnit() != null ? request.getUnit() : "个")
                .build();
        product = productRepository.save(product);
        log.info("创建商品成功: id={}, sku={}", product.getId(), product.getSku());
        return toResponse(product);
    }

    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "商品不存在"));
        product.setName(request.getName());
        if (request.getUnit() != null) {
            product.setUnit(request.getUnit());
        }
        product = productRepository.save(product);
        return toResponse(product);
    }

    /**
     * 删除商品（逻辑删除 + 渐进式确认提示）。
     *
     * 删除 = 置 deleted=true（实体 @SQLDelete 将 deleteById 转为 UPDATE），
     * 不物理删除、不清理关联数据：库存与历史入库/出库记录全部保留、可追溯。
     * 已删商品被 @SQLRestriction 自动过滤：列表/搜索/详情/编辑/入库/出库引用一律 404 不可见。
     *
     * 渐进式确认：有关联数据（库存/历史入库/出库记录）且未传 force 时，
     * 先返回 400 提示关联数量与影响范围，由前端二次确认后带 force=true 重试；
     * force=true 只是"确认删除"（仍为逻辑删除，不清理任何关联数据）。
     */
    @Transactional
    public void delete(Long id, boolean force) {
        if (!productRepository.existsById(id)) {
            throw new BusinessException(404, "商品不存在");
        }
        long inventoryCount = inventoryRepository.countByProductId(id);
        long inboundCount = inboundOrderItemRepository.countByProductId(id);
        long outboundCount = outboundOrderItemRepository.countByProductId(id);

        if ((inventoryCount > 0 || inboundCount > 0 || outboundCount > 0) && !force) {
            throw new BusinessException(400,
                    "该商品存在库存 " + inventoryCount + " 条、历史入库记录 " + inboundCount
                            + " 条、出库记录 " + outboundCount
                            + " 条；删除后商品将不可见，历史单据与库存记录保留可追溯，请确认后重试");
        }
        productRepository.deleteById(id);
        log.info("逻辑删除商品: id={}, force={}, 关联库存={}条/入库记录={}条/出库记录={}条",
                id, force, inventoryCount, inboundCount, outboundCount);
    }

    private ProductResponse toResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .sku(product.getSku())
                .unit(product.getUnit())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
