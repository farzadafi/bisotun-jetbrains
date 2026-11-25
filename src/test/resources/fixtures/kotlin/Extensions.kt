package com.example.extensions

import com.example.models.Order
import com.example.models.OrderItem

fun Order.describe(): String = "$id:${items.size}"

fun Order.withItem(item: OrderItem): Order = copy(items = items + item)
