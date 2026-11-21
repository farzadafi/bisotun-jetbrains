package main

import (
	"example.com/bisotun/service"
)

func main() {
	repository := service.NewOrderRepository()
	engine := service.NewOrderService(repository, nil)
	order, err := engine.Create(service.CreateOrderRequest{CustomerID: "c-1"})
	if err != nil {
		panic(err)
	}
	go audit(order.ID)
	println(order.ID)
}

func audit(id string) {
	defer recover()
	println("audit", id)
}
