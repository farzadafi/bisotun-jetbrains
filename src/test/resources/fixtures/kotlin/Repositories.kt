package com.example.repository

import com.example.models.Order

interface OrderRepository {
    fun save(order: Order): Order
    fun find(id: String): Order?
}

class InMemoryOrderRepository : OrderRepository {
    private val orders = mutableMapOf<String, Order>()

    override fun save(order: Order): Order {
        orders[order.id] = order
        audit(order.id)
        return order
    }

    override fun find(id: String): Order? = orders[id]

    private fun audit(orderId: String) {
        log(orderId)
    }

    private fun log(message: String) {
        println(message)
    }
}

class DatabaseOrderRepository(private val gateway: Gateway) : OrderRepository {
    override fun save(order: Order): Order {
        gateway.write(order)
        return order
    }

    override fun find(id: String): Order? = gateway.read(id)
}

interface Gateway {
    fun write(order: Order)
    fun read(id: String): Order?
}

class SqlGateway : Gateway {
    override fun write(order: Order) {
        execute("insert")
    }

    override fun read(id: String): Order? {
        execute("select")
        return null
    }

    private fun execute(sql: String) {
        println(sql)
    }
}
