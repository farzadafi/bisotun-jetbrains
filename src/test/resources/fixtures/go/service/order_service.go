package service

import (
	"fmt"

	"example.com/bisotun/notify"
)

// Order is the project-local aggregate returned by the service.
type Order struct {
	ID       string
	Total    float64
	Customer Customer
}

type Customer struct {
	Name string
}

type CreateOrderRequest struct {
	CustomerID string
	Items      []OrderItem
}

type OrderItem struct {
	SKU      string
	Quantity int
}

type Repository[T any] interface {
	Save(T) error
	Find(id string) (T, error)
}

type Notifier interface {
	Notify(message string) error
}

type OrderService struct {
	repo     Repository[Order]
	notifier Notifier
}

func NewOrderService(repo Repository[Order], notifier Notifier) *OrderService {
	return &OrderService{repo: repo, notifier: notifier}
}

func (s *OrderService) Create(request CreateOrderRequest) (Order, error) {
	order, err := s.buildOrder(request)
	if err != nil {
		return Order{}, err
	}
	if err := s.repo.Save(order); err != nil {
		return Order{}, err
	}
	if err := s.notifier.Notify(fmt.Sprintf("order %s", order.ID)); err != nil {
		return Order{}, err
	}
	return order, nil
}

func (s *OrderService) buildOrder(request CreateOrderRequest) (Order, error) {
	var total float64
	for _, item := range request.Items {
		total += normalize(item.Quantity)
	}
	id := newID(request.CustomerID)
	switch total {
	case 0:
		return Order{}, nil
	}
	return Order{ID: id, Total: total}, nil
}

func (s OrderService) describe() string {
	return "service"
}

func normalize(quantity int) float64 {
	return float64(quantity)
}

// deliver must stay unresolved: ExternalSender is not a project declaration.
func (s *OrderService) deliver(external ExternalSender, client interface{}) error {
	return external.Send("hello")
}

func (s *OrderService) dispatch(client Client) error {
	return client.Save(Order{})
}

func notifyAdmins() error {
	return notify.NewEmailNotifier("ops@example.com").Notify("deployed")
}
