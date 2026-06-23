package com.dentapinos.dataguard.test.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность-join table с дополнительными полями для связи Order <-> Product.
 * Пример: ProductOrder представляет одну строку в заказе с указанием количества и цены.
 */
@Entity
@Table(name = "product_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    private Integer quantity;

    private Double totalPrice;
}
