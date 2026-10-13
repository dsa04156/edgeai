package runtime

import (
	"context"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
)

func TestObservationSessionSurvivesRestartAndBackwardClock(t *testing.T) {
	path := filepath.Join(t.TempDir(), "observation-session")
	first, err := nextObservationSession(context.Background(), path, 2000)
	if err != nil {
		t.Fatal(err)
	}
	// Reopening the durable state models a different Mapper process after a
	// backward clock adjustment. Incarnation order must still advance.
	second, err := nextObservationSession(context.Background(), path, 1000)
	if err != nil {
		t.Fatal(err)
	}
	a, _ := strconv.ParseInt(first, 36, 64)
	b, _ := strconv.ParseInt(second, 36, 64)
	if b <= a {
		t.Fatalf("restart rejected after clock rollback: %d then %d", a, b)
	}
	persisted, err := os.ReadFile(path)
	if err != nil || string(persisted) != strconv.FormatInt(b, 10) {
		t.Fatal("session was not durably committed", err)
	}
}

func TestConcurrentMapperCollectorsAllocateDistinctDurableSessions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "observation-session")
	var workers sync.WaitGroup
	values := make(chan string, 32)
	for i := 0; i < cap(values); i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			v, err := nextObservationSession(context.Background(), path, 1000)
			if err != nil {
				t.Error(err)
				return
			}
			values <- v
		}()
	}
	workers.Wait()
	close(values)
	seen := map[string]bool{}
	for v := range values {
		if seen[v] {
			t.Fatal("two collectors share an incarnation", v)
		}
		seen[v] = true
	}
	if len(seen) != 32 {
		t.Fatal("missing sessions", len(seen))
	}
}

func TestObservationSessionCannotFallBackWhenDurableStateIsCorrupt(t *testing.T) {
	path := filepath.Join(t.TempDir(), "observation-session")
	if err := os.WriteFile(path, []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := nextObservationSession(context.Background(), path, 1000); err == nil {
		t.Fatal("corrupt state silently reset incarnation")
	}
	if _, err := nextObservationSession(context.Background(), "", 1000); err == nil {
		t.Fatal("qualified session without persistent state")
	}
}
