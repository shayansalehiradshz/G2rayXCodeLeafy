package splithttp

import (
	"context"
	"crypto/tls"
	"io"
	stdnet "net"
	"sync/atomic"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
	xtls "github.com/xtls/xray-core/transport/internet/tls"
	"golang.org/x/net/http2"
)

type g2rayLeakConn struct {
	stdnet.Conn
	closed atomic.Bool
}

func (c *g2rayLeakConn) Close() error { c.closed.Store(true); return c.Conn.Close() }

type g2rayLeakDialer struct{ conn stdnet.Conn }

func (d *g2rayLeakDialer) Dial(context.Context, xnet.Address, xnet.Destination, *internet.SocketConfig) (stdnet.Conn, error) {
	return d.conn, nil
}
func (d *g2rayLeakDialer) DestIpAddress() xnet.IP { return nil }

func TestG2RayXhttpFailedTLSClosesSocket(t *testing.T) {
	raw, peer := stdnet.Pipe()
	defer raw.Close()
	defer peer.Close()
	tracked := &g2rayLeakConn{Conn: raw}
	internet.UseAlternativeSystemDialer(&g2rayLeakDialer{conn: tracked})
	defer internet.UseAlternativeSystemDialer(nil)
	go func() {
		b := make([]byte, 32768)
		_, _ = peer.Read(b)
		_, _ = peer.Write([]byte("HTTP/1.1 400 Bad Request\r\n\r\n"))
		_, _ = io.Copy(io.Discard, peer)
	}()
	settings := &internet.MemoryStreamConfig{ProtocolName: "splithttp", ProtocolSettings: &Config{Host: "example.com"}, SecurityType: "tls", SecuritySettings: &xtls.Config{ServerName: "example.com", Fingerprint: "chrome", NextProtocol: []string{"h2"}}}
	dest := xnet.TCPDestination(xnet.DomainAddress("example.com"), 443)
	client := createHTTPClient(dest, settings).(*DefaultDialerClient)
	h2 := client.client.Transport.(*http2.Transport)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := h2.DialTLSContext(ctx, "tcp", "example.com:443", &tls.Config{})
	if conn != nil {
		_ = conn.Close()
	}
	if err == nil {
		t.Fatal("invalid TLS response unexpectedly succeeded")
	}
	if !tracked.closed.Load() {
		t.Fatalf("TLS handshake failed but XHTTP socket was left open: %v", err)
	}
}

func TestG2RayXhttpCancelBlackholedTLSSetup(t *testing.T) {
	raw, peer := stdnet.Pipe()
	defer raw.Close()
	defer peer.Close()
	tracked := &g2rayLeakConn{Conn: raw}
	internet.UseAlternativeSystemDialer(&g2rayLeakDialer{conn: tracked})
	defer internet.UseAlternativeSystemDialer(nil)
	handshakeStarted := make(chan struct{})
	go func() {
		b := make([]byte, 32768)
		_, _ = peer.Read(b)
		close(handshakeStarted)
		_, _ = io.Copy(io.Discard, peer)
	}()
	settings := &internet.MemoryStreamConfig{ProtocolName: "splithttp", ProtocolSettings: &Config{Host: "example.com", Mode: "packet-up", Path: "/"}, SecurityType: "tls", SecuritySettings: &xtls.Config{ServerName: "example.com", Fingerprint: "chrome", NextProtocol: []string{"h2"}}}
	dest := xnet.TCPDestination(xnet.DomainAddress("example.com"), 443)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan error, 1)
	go func() {
		conn, err := Dial(ctx, dest, settings)
		if conn != nil {
			_ = conn.Close()
		}
		result <- err
	}()
	select {
	case <-handshakeStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("handshake did not start")
	}
	cancel()
	select {
	case err := <-result:
		t.Logf("canceled XHTTP dial returned: %v", err)
	case <-time.After(500 * time.Millisecond):
		_ = peer.Close()
		select {
		case <-result:
		case <-time.After(3 * time.Second):
			t.Fatal("test cleanup could not stop dial")
		}
		t.Error("canceled XHTTP dial stayed blocked during blackholed TLS setup for >500ms")
	}
}
