package broken

import "fmt"

type Broken struct {
	Field string

func (b *Broken) Run() {
	if true {
		fmt.Println("x")
	}
