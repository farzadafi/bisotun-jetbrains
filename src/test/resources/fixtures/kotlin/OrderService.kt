package com.example.service

import com.example.extensions.describe
import com.example.extensions.withItem
import com.example.models.Order
import com.example.models.OrderItem
import com.example.models.OrderRequest
import com.example.models.Receipt
import com.example.repository.OrderRepository

class OrderService(
    private val repository: OrderRepository,
    private val notifier: Notifier
) {
    fun create(request: OrderRequest): Receipt {
        val order = buildOrder(request)
        val saved = repository.save(order)
        notifier.notify(saved)
        return Receipt(saved.id, saved.items.size)
    }

    fun buildOrder(request: OrderRequest): Order {
        val items = request.items.map { normalize(it) }
        val order = Order(request.customerId, items)
        return addItem(order, OrderItem("gift", 1))
    }

    private fun addItem(order: Order, item: OrderItem): Order = order.withItem(item)

    private fun normalize(item: OrderItem): OrderItem = item.copy(quantity = maxOf(1, item.quantity))

    fun overload(value: String): String = "s:$value"

    fun overload(value: Int): String = "i:$value"

    fun runOverloads() {
        overload("a")
        overload(1)
    }

    fun useLambdas(items: List<OrderItem>): Int {
        val transform = { item: OrderItem -> normalize(item) }
        return items.map(transform).size
    }

    fun callable(): (String) -> String = ::overload

    fun extensionDemo(order: Order): String {
        val updated = order.withItem(OrderItem("extra", 2))
        return updated.describe()
    }

    fun localFunctionBody(): Int {
        fun nested(): Int = helper(2)
        return 0
    }

    fun ping(depth: Int) {
        pong(depth)
    }

    fun pong(depth: Int) {
        if (depth > 0) ping(depth - 1)
    }

    val handler: (Int) -> Int = { value -> helper(value) }

    private fun helper(value: Int): Int = value + 1
}

class Notifier {
    fun notify(order: Order) {
        send(order.id)
    }

    private fun send(message: String) {
        println(message)
    }
}
