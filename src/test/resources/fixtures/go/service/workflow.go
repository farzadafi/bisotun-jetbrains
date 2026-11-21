package service

import "sync"

// Job is a generic model used by the workflow helpers.
type Job[T any] struct {
	Item T
}

// Handler is a generic function type.
type Handler[T any] func(T) error

// RunJobs exercises generics, closures, goroutines, channels, select and a type switch.
func RunJobs[T any](jobs []Job[T], handler Handler[T]) error {
	results := make(chan error, len(jobs))
	var group sync.WaitGroup
	for _, job := range jobs {
		go func(item T) {
			defer group.Done()
			results <- handler(item)
		}(job.Item)
	}
	go func() {
		group.Wait()
		close(results)
	}()
	select {
	case err := <-results:
		if err != nil {
			return err
		}
	default:
	}
	switch value := any(jobs).(type) {
	case []Job[T]:
		_ = value
	}
	return nil
}

// Retry is a plain generic function whose result type is a type parameter.
func Retry[T any](value T) (T, error) {
	return value, nil
}
