package dmi

import (
	"context"
	"path/filepath"
	"sort"
	"sync"
	"testing"
	"time"
)

func TestProtocolProcessesShareOneNodeBudgetAndCanCancel(t *testing.T) {
	path := filepath.Join(t.TempDir(), "budget")
	var mu sync.Mutex
	var times []time.Time
	var wg sync.WaitGroup
	for i := 0; i < 6; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := waitBudget(context.Background(), path, 30*time.Millisecond); err != nil {
				t.Error(err)
				return
			}
			mu.Lock()
			times = append(times, time.Now())
			mu.Unlock()
		}()
	}
	wg.Wait()
	sort.Slice(times, func(i, j int) bool { return times[i].Before(times[j]) })
	if len(times) != 6 {
		t.Fatal(times)
	}
	for i := 1; i < len(times); i++ {
		if times[i].Sub(times[i-1]) < 28*time.Millisecond {
			t.Fatal("node budget exceeded", times)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := waitBudget(ctx, path, time.Second); err == nil {
		t.Fatal("cancelled report waited")
	}
}
