package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.dto.ProductCreateRequest;
import com.wms.dto.ProductResponse;
import com.wms.dto.ProductUpdateRequest;
import com.wms.entity.Product;
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
     * 删除商品（逻辑删除）。
     *
     * 删除 = 置 deleted=true（实体 @SQLDelete 将 deleteById 转为 UPDATE），
     * 不物理删除、不清理关联数据：库存与历史入库/出库记录全部保留，
     * 历史可追溯（任务 3 预埋 Bug 的"孤立库存"问题由"不再物理删除"根治）。
     * 已删商品被 @SQLRestriction 自动过滤：列表/搜索/详情/编辑/入库/出库引用一律 404 不可见。
     */
    @Transactional
    public void delete(Long id) {
        if (!productRepository.existsById(id)) {
            throw new BusinessException(404, "商品不存在");
        }
        productRepository.deleteById(id);
        log.info("逻辑删除商品: id={}", id);
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
