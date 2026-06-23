-- Создание таблиц для интеграционных тестов

-- Таблица users (с уникальным email)
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE
);

-- Таблица user_profiles (уникальный user_id для OneToOne)
CREATE TABLE IF NOT EXISTS user_profiles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    bio TEXT,
    avatar_url VARCHAR(255),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Таблица categories (с уникальным name)
CREATE TABLE IF NOT EXISTS categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT
);

-- Таблица products (связи с категориями и пользователями)
CREATE TABLE IF NOT EXISTS products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(10, 2) NOT NULL,
    stock_quantity INT NOT NULL,
    category_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    FOREIGN KEY (category_id) REFERENCES categories(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Таблица orders (с уникальным order_number)
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_number VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(50) NOT NULL,
    user_id BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Таблица order_products (ManyToMany между Order и Product)
CREATE TABLE IF NOT EXISTS order_products (
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    PRIMARY KEY (order_id, product_id),
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (product_id) REFERENCES products(id)
);

-- Таблица product_orders (сущность-связка для связи Order <-> Product с доп. полями)
CREATE TABLE IF NOT EXISTS product_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    order_id BIGINT,
    quantity INT,
    total_price DECIMAL(10, 2),
    FOREIGN KEY (product_id) REFERENCES products(id),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);

-- Таблица product_order_users (ManyToMany между User и ProductOrder)
CREATE TABLE IF NOT EXISTS product_order_users (
    user_id BIGINT NOT NULL,
    product_order_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, product_order_id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (product_order_id) REFERENCES product_orders(id)
);
