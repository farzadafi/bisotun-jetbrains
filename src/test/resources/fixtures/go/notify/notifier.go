package notify

import "fmt"

type EmailNotifier struct {
	Address string
}

func NewEmailNotifier(address string) *EmailNotifier {
	return &EmailNotifier{Address: address}
}

func (n *EmailNotifier) Notify(message string) error {
	return send(n.Address, message)
}

func send(address string, message string) error {
	_, err := fmt.Println(address, message)
	return err
}
