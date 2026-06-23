package com.dentapinos.dataguard.test.domain;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class EntityBuilder{

    private static Random random = new Random();
    private static AtomicInteger counter = new AtomicInteger();
    private static AtomicInteger counterProfiles = new AtomicInteger();
    private static AtomicInteger counterCategory = new AtomicInteger();
    private static AtomicInteger counterProduct = new AtomicInteger();
    private static AtomicInteger counterOrder = new AtomicInteger();
    private static AtomicInteger counterPO = new AtomicInteger();

    public static User user(){
        int c = counter.incrementAndGet();
        User user = new User();
        user.setEmail("email"+c+"@email.com");
        user.setUsername("user"+c);
        return user;
    }

    public static UserProfile profile(@Nullable User user){
        int c = counterProfiles.incrementAndGet();
        return UserProfile.builder()
                .user(user)
                .bio("Java developer " + c)
                .avatarUrl("https://example.com/avatar"+c+".jpg")
                .build();
    }

    public static Category category(){
        int c = counterCategory.incrementAndGet();
        return Category.builder()
                .name("Electronics " + c)
                .description("Electronic devices and gadgets " + c)
                .build();
    }

    public static Product product(@Nullable Category category, @Nullable User user){
        int c = counterProduct.incrementAndGet();
        return Product.builder()
                .name("product " + c)
                .description("DESC " + UUID.randomUUID())
                .price(random.nextDouble(0, 5000))
                .stockQuantity(random.nextInt(0, 100))
                .category(category)
                .user(user)
                .build();
    }

    public static Order order(@Nullable User user, OrderStatus status){
        int c = counterOrder.incrementAndGet();
        return Order.builder()
                .orderNumber(UUID.randomUUID() + "" + c)
                .status(status)
                .user(user)
                .build();
    }

    public static void orderAddProducts(Order order, List<Product> products){
        products.stream().map(p -> order.getProducts().add(p));
    }

    public static ProductOrder productOrder(Product product){
        int c = counterPO.incrementAndGet();
        return ProductOrder.builder()
                .product(product)
                .quantity(random.nextInt(0, 100))
                .totalPrice(random.nextDouble(0, 5000) + c)
                .build();
    }


}

