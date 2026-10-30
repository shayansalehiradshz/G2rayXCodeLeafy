package outbound

import (
	"context"
	"errors"
	"io"
	stdnet "net"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/splithttp"
	"github.com/xtls/xray-core/transport/internet/stat"
	xtls "github.com/xtls/xray-core/transport/internet/tls"
)

type xhttpSystemDialer struct{ conn stdnet.Conn }

func (d *xhttpSystemDialer) Dial(context.Context, xnet.Address, xnet.Destination, *internet.SocketConfig) (stdnet.Conn, error) {
	return d.conn, nil
}
func (*xhttpSystemDialer) DestIpAddress() xnet.IP { return nil }

type xhttpOutboundDialer struct {
	dest     xnet.Destination
	settings *internet.MemoryStreamConfig
}

func (d *xhttpOutboundDialer) Dial(ctx context.Context, _ xnet.Destination) (stat.Connection, error) {
	conn, err := splithttp.Dial(ctx, d.dest, d.settings)
	if conn != nil {
		_ = conn.Close()
	}
	if err != nil {
		return nil, err
	}
	return nil, errors.New("fixture ending after XHTTP dial")
}
func (*xhttpOutboundDialer) DestIpAddress() xnet.IP                                { return nil }
func (*xhttpOutboundDialer) SetOutboundGateway(context.Context, *session.Outbound) {}

func TestG2RayVLESSCancellationBlockedInsideXHTTP(t *testing.T) {
	raw, peer := stdnet.Pipe()
	defer raw.Close()
	defer peer.Close()
	internet.UseAlternativeSystemDialer(&xhttpSystemDialer{conn: raw})
	defer internet.UseAlternativeSystemDialer(nil)
	started := make(chan struct{})
	go func() {
		b := make([]byte, 32768)
		_, _ = peer.Read(b)
		close(started)
		_, _ = io.Copy(io.Discard, peer)
	}()
	dest := xnet.TCPDestination(xnet.DomainAddress("example.com"), 443)
	settings := &internet.MemoryStreamConfig{ProtocolName: "splithttp", ProtocolSettings: &splithttp.Config{Host: "example.com", Mode: "packet-up", Path: "/"}, SecurityType: "tls", SecuritySettings: &xtls.Config{ServerName: "example.com", Fingerprint: "chrome", NextProtocol: []string{"h2"}}}
	parent, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx := session.ContextWithOutbounds(parent, []*session.Outbound{{Target: dest}})
	handler := &Handler{server: protocol.NewServerSpec(dest, nil)}
	dialer := &xhttpOutboundDialer{dest: dest, settings: settings}
	done := make(chan error, 1)
	go func() { done <- handler.Process(ctx, nil, dialer) }()
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("handshake did not start")
	}
	cancel()
	select {
	case err := <-done:
		t.Logf("actual VLESS handler returned after cancellation: %v", err)
	case <-time.After(500 * time.Millisecond):
		_ = peer.Close()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Fatal("test cleanup could not stop handler")
		}
		t.Error("canceled VLESS handler stayed blocked >500ms inside actual XHTTP setup")
	}
}
