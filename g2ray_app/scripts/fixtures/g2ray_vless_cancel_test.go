package outbound

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/transport/internet/stat"
)

type g2rayCancelDialer struct {
	calls atomic.Int32
	dial  func(context.Context) error
}

func (d *g2rayCancelDialer) Dial(ctx context.Context, _ net.Destination) (stat.Connection, error) {
	d.calls.Add(1)
	return nil, d.dial(ctx)
}

func (*g2rayCancelDialer) DestIpAddress() net.IP                                 { return nil }
func (*g2rayCancelDialer) SetOutboundGateway(context.Context, *session.Outbound) {}

func TestG2RayVLESSCancellationStopsActualOutboundRetries(t *testing.T) {
	for _, phase := range []string{"before-dial", "during-dial", "during-backoff"} {
		t.Run(phase, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			ctx = session.ContextWithOutbounds(ctx, []*session.Outbound{{
				Target: net.TCPDestination(net.DomainAddress("destination.invalid"), 443),
			}})
			dialer := &g2rayCancelDialer{}
			reached := make(chan struct{})
			dialer.dial = func(ctx context.Context) error {
				switch phase {
				case "during-dial":
					if dialer.calls.Load() == 1 {
						close(reached)
					}
					<-ctx.Done()
					return ctx.Err()
				case "during-backoff":
					if dialer.calls.Load() == 2 {
						close(reached)
					}
					return errors.New("controlled transient dial failure")
				default:
					return ctx.Err()
				}
			}
			if phase == "before-dial" {
				cancel()
			}
			handler := &Handler{server: protocol.NewServerSpec(
				net.TCPDestination(net.DomainAddress("server.invalid"), 443), nil)}
			done := make(chan error, 1)
			go func() { done <- handler.Process(ctx, nil, dialer) }()
			if phase != "before-dial" {
				select {
				case <-reached:
				case <-time.After(2 * time.Second):
					t.Fatal("dial did not reach the controlled cancellation point")
				}
			}
			started := time.Now()
			cancel()
			select {
			case err := <-done:
				if !errors.Is(err, context.Canceled) {
					t.Fatalf("cancellation was not preserved: %v", err)
				}
			case <-time.After(500 * time.Millisecond):
				t.Fatal("canceled outbound kept dialing or sleeping")
			}
			want := map[string]int32{"before-dial": 0, "during-dial": 1, "during-backoff": 2}[phase]
			if dialer.calls.Load() != want || time.Since(started) > 500*time.Millisecond {
				t.Fatalf("calls=%d want=%d cancellation took=%s", dialer.calls.Load(), want, time.Since(started))
			}
		})
	}
}
