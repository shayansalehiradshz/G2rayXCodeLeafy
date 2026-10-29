package retry

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

// The context-aware VLESS retry must not call an already-canceled dialer.
// Simulate a dialer that correctly returns the already-canceled ctx error.
func TestG2RayCanceledVlessDialDoesNotRetry(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	calls := 0
	started := time.Now()
	err := ExponentialBackoffContext(ctx, 5, 200).On(func() error { calls++; return ctx.Err() })
	if !errors.Is(err, context.Canceled) {
		t.Fatal("canceled dial unexpectedly succeeded")
	}
	if calls != 0 || time.Since(started) > 250*time.Millisecond {
		t.Fatalf("canceled dial made %d attempts and took %s", calls, time.Since(started))
	}
}

func TestG2RayRetryStopsWhenCanceledDuringBackoff(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	reachedSecond := make(chan struct{})
	var calls atomic.Int32
	done := make(chan error, 1)
	go func() {
		done <- ExponentialBackoffContext(ctx, 5, 1000).On(func() error {
			if calls.Add(1) == 2 {
				close(reachedSecond)
			}
			return errors.New("simulated dial failure")
		})
	}()
	<-reachedSecond
	started := time.Now()
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) || calls.Load() != 2 {
			t.Fatalf("err=%v calls=%d", err, calls.Load())
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("cancellation did not interrupt the retry delay")
	}
	if time.Since(started) > 500*time.Millisecond {
		t.Fatal("cancellation took too long")
	}
}

func TestG2RayRetryKeepsAttemptsAndSkipsLastSleep(t *testing.T) {
	calls := 0
	started := time.Now()
	err := ExponentialBackoffContext(context.Background(), 2, 1000).On(func() error {
		calls++
		return errors.New("simulated dial failure")
	})
	if err == nil || calls != 2 || time.Since(started) > 500*time.Millisecond {
		t.Fatalf("err=%v calls=%d elapsed=%s", err, calls, time.Since(started))
	}
}

func TestG2RayRetryReturnsSuccessWithoutExtraAttempt(t *testing.T) {
	calls := 0
	err := ExponentialBackoffContext(context.Background(), 5, 1000).On(func() error {
		calls++
		if calls == 2 {
			return nil
		}
		return errors.New("temporary")
	})
	if err != nil || calls != 2 {
		t.Fatalf("err=%v calls=%d", err, calls)
	}
}

func TestG2RayRetryPreservesExpiredDeadline(t *testing.T) {
	ctx, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
	defer cancel()
	calls := 0
	err := ExponentialBackoffContext(ctx, 5, 200).On(func() error { calls++; return nil })
	if !errors.Is(err, context.DeadlineExceeded) || calls != 0 {
		t.Fatalf("err=%v calls=%d", err, calls)
	}
}
