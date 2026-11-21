package service

import "errors"

type OrderRepository struct {
	orders []Order
}

func NewOrderRepository() *OrderRepository {
	return &OrderRepository{}
}

func (r *OrderRepository) Save(order Order) error {
	if order.ID == "" {
		return errors.New("missing id")
	}
	r.orders = append(r.orders, order)
	return r.persist()
}

func (r *OrderRepository) persist() error {
	return nil
}

func newID(customerID string) string {
	return "order-" + customerID
}
