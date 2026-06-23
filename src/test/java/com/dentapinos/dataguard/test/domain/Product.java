package com.dentapinos.dataguard.test.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Сущность с @ManyToOne связью.
 * Пример: Product -> Category
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String description;

    private Double price;

    private Integer stockQuantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToMany
    @JoinTable(
            name = "order_products",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "order_id")
    )
    private Set<Order> orders = new HashSet<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProductOrder> productsOrders = new ArrayList<>();

    public void addCategory(Category category) {
        this.category = category;
        if (category.getProducts() == null) {
            category.setProducts(new ArrayList<>());
        }
        if (!category.getProducts().contains(this)) {
            category.getProducts().add(this);
        }
    }

    public void addOrder(Order order) {
        if (this.orders == null) {
            this.orders = new HashSet<>();
        }
        if (!this.orders.contains(order)) {
            this.orders.add(order);
        }
    }

    public void addProductOrder(ProductOrder productOrder) {
        if (this.productsOrders == null) {
            this.productsOrders = new ArrayList<>();
        }
        if (!this.productsOrders.contains(productOrder)) {
            this.productsOrders.add(productOrder);
            productOrder.setProduct(this);
        }
    }
}
