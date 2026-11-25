package com.example.models

data class OrderItem(val sku: String, val quantity: Int)

data class OrderRequest(val customerId: String, val items: List<OrderItem>)

data class Order(val id: String, val items: List<OrderItem>)

data class Receipt(val orderId: String, val total: Int)
