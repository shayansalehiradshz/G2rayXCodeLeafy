package websocket

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha1"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	stdnet "net"
	"net/http"
	"net/url"
	"sync/atomic"
	"testing"
	"testing/iotest"
	"time"

	gorillaws "github.com/gorilla/websocket"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol/tls/cert"
	"github.com/xtls/xray-core/testing/servers/tcp"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/stat"
	xtls "github.com/xtls/xray-core/transport/internet/tls"
)

type g2rayDataEOFConn struct {
	stdnet.Conn
	inject atomic.Bool
}

func (c *g2rayDataEOFConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if c.inject.Load() && len(p) >= 8192 && n > 0 {
		return n, io.EOF
	}
	return n, err
}

// Exercise a real Gorilla WebSocket message reader with an underlying
// io.Reader returning the legal (n > 0, EOF) result. uTLS can return this on
// final application data followed by a buffered close-notify.
func TestG2RayWSRealFramePreservesDataWithEOF(t *testing.T) {
	raw, peer := stdnet.Pipe()
	defer raw.Close()
	defer peer.Close()
	wrapped := &g2rayDataEOFConn{Conn: raw}
	payload := bytes.Repeat([]byte("z"), 8192)
	sendFrame := make(chan struct{})
	serverDone := make(chan error, 1)
	go func() {
		req, err := http.ReadRequest(bufio.NewReader(peer))
		if err != nil {
			serverDone <- err
			return
		}
		sum := sha1.Sum([]byte(req.Header.Get("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
		_, err = fmt.Fprintf(peer, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n", base64.StdEncoding.EncodeToString(sum[:]))
		if err != nil {
			serverDone <- err
			return
		}
		<-sendFrame
		if _, err = peer.Write([]byte{0x82, 126, 0x20, 0x00}); err == nil {
			_, err = peer.Write(payload)
		}
		serverDone <- err
	}()
	endpoint, err := url.Parse("ws://example.com/")
	if err != nil {
		t.Fatal(err)
	}
	ws, _, err := gorillaws.NewClient(wrapped, endpoint, nil, 4096, 4096)
	if err != nil {
		t.Fatal(err)
	}
	wrapped.inject.Store(true)
	close(sendFrame)
	c := NewConnection(ws, nil, nil, 0)
	got := make([]byte, len(payload))
	n, err := c.Read(got)
	if n != len(payload) || !bytes.Equal(got[:n], payload) {
		t.Fatalf("real WebSocket frame yielded %d/%d bytes: %v", n, len(payload), err)
	}
	if err := <-serverDone; err != nil {
		t.Fatal(err)
	}
	n, err = c.Read(make([]byte, 1))
	if n != 0 || !errors.Is(err, io.EOF) {
		t.Fatalf("expected EOF after final frame, got n=%d err=%v", n, err)
	}
}

type g2rayObservedConn struct {
	stdnet.Conn
	closed atomic.Bool
}

// An io.Reader may return data together with EOF; dropping that data violates
// io.Reader and can truncate a WebSocket byte stream.
func TestG2RayWSReadPreservesDataWithEOF(t *testing.T) {
	payload := []byte("final websocket bytes")
	c := &connection{reader: iotest.DataErrReader(bytes.NewReader(payload))}
	defer func() {
		if r := recover(); r != nil {
			t.Errorf("read discarded final bytes and reached the next reader: %v", r)
		}
	}()
	got := make([]byte, len(payload))
	n, err := c.Read(got)
	if n != len(payload) || !bytes.Equal(got[:n], payload) {
		t.Fatalf("read %d bytes %q, want %q (err=%v)", n, got[:n], payload, err)
	}
}

// This checks the actual Xray WSS stack, rather than only the synthetic
// reader, when a peer closes TLS immediately after the last frame.
func TestG2RayWSFinalFrameOnTLSClose(t *testing.T) {
	port := tcp.PickPort()
	certificate, certHash := cert.MustGenerate(nil, cert.CommonName("localhost"))
	settings := &internet.MemoryStreamConfig{
		ProtocolName:     "websocket",
		ProtocolSettings: &Config{Path: "/"},
		SecurityType:     "tls",
		SecuritySettings: &xtls.Config{
			Certificate:          []*xtls.Certificate{xtls.ParseCertificate(certificate)},
			PinnedPeerCertSha256: [][]byte{certHash[:]},
		},
	}
	payload := bytes.Repeat([]byte("x"), 8192)
	writeNow := make(chan struct{})
	written := make(chan error, 1)
	listener, err := ListenWS(context.Background(), xnet.LocalHostIP, port, settings, func(conn stat.Connection) {
		go func() {
			<-writeNow
			_, err := conn.Write(payload)
			if err == nil {
				err = conn.(*connection).conn.UnderlyingConn().Close()
			}
			written <- err
		}()
	})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	conn, err := Dial(context.Background(), xnet.TCPDestination(xnet.DomainAddress("localhost"), port), settings)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	close(writeNow)
	if err := <-written; err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	got := make([]byte, len(payload))
	n, err := io.ReadFull(conn, got)
	if err != nil || !bytes.Equal(got, payload) {
		t.Fatalf("final TLS frame: got %d/%d bytes, err=%v", n, len(payload), err)
	}
}

func (c *g2rayObservedConn) Close() error { c.closed.Store(true); return c.Conn.Close() }

type g2rayFakeDialer struct {
	dial func(context.Context) (stdnet.Conn, error)
}

func (d *g2rayFakeDialer) Dial(ctx context.Context, _ xnet.Address, _ xnet.Destination, _ *internet.SocketConfig) (stdnet.Conn, error) {
	return d.dial(ctx)
}
func (d *g2rayFakeDialer) DestIpAddress() xnet.IP { return nil }

func g2rayWSSettings(fingerprint string) *internet.MemoryStreamConfig {
	return &internet.MemoryStreamConfig{
		ProtocolName:     "websocket",
		ProtocolSettings: &Config{Path: "/"},
		SecurityType:     "tls",
		SecuritySettings: &xtls.Config{ServerName: "example.com", Fingerprint: fingerprint},
	}
}
func g2rayWSDest() xnet.Destination {
	return xnet.TCPDestination(xnet.DomainAddress("example.com"), xnet.Port(443))
}

// Gorilla's 8s handshake deadline must reach the socket dialer, including
// configurations with and without uTLS. The synthetic dialer fails immediately.
func TestG2RayWSHandshakeDeadlineReachesDialer(t *testing.T) {
	for _, fingerprint := range []string{"chrome", "unsafe"} {
		t.Run(fingerprint, func(t *testing.T) {
			var remaining time.Duration
			internet.UseAlternativeSystemDialer(&g2rayFakeDialer{dial: func(ctx context.Context) (stdnet.Conn, error) {
				deadline, ok := ctx.Deadline()
				if !ok {
					return nil, errors.New("no deadline")
				}
				remaining = time.Until(deadline)
				return nil, errors.New("synthetic dial failure")
			}})
			defer internet.UseAlternativeSystemDialer(nil)
			ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
			defer cancel()
			_, err := Dial(ctx, g2rayWSDest(), g2rayWSSettings(fingerprint))
			if err == nil {
				t.Fatal("synthetic dial unexpectedly succeeded")
			}
			if remaining <= 0 || remaining > 9*time.Second {
				t.Fatalf("dialer received %s remaining; expected Gorilla's <=8s deadline", remaining)
			}
		})
	}
}

// A connected peer reads ClientHello and never answers TLS. A long parent
// deadline must not defeat the 8-second WebSocket handshake budget.
func TestG2RayWSBlackholedTLSHandshakeIsBounded(t *testing.T) {
	raw, peer := stdnet.Pipe()
	defer raw.Close()
	defer peer.Close()
	internet.UseAlternativeSystemDialer(&g2rayFakeDialer{dial: func(context.Context) (stdnet.Conn, error) { return raw, nil }})
	defer internet.UseAlternativeSystemDialer(nil)
	go func() { _, _ = io.Copy(io.Discard, peer) }()
	ctx, cancel := context.WithTimeout(context.Background(), 9500*time.Millisecond)
	defer cancel()
	started := time.Now()
	conn, err := Dial(ctx, g2rayWSDest(), g2rayWSSettings("chrome"))
	elapsed := time.Since(started)
	if conn != nil {
		_ = conn.Close()
	}
	if err == nil {
		t.Fatal("silent peer unexpectedly completed TLS")
	}
	if elapsed > 8750*time.Millisecond {
		t.Fatalf("silent TLS handshake took %s; intended deadline is 8s", elapsed)
	}
}

func TestG2RayWSEarlierParentCancellationClosesSocket(t *testing.T) {
	raw, peer := stdnet.Pipe()
	tracked := &g2rayObservedConn{Conn: raw}
	defer raw.Close()
	defer peer.Close()
	internet.UseAlternativeSystemDialer(&g2rayFakeDialer{dial: func(context.Context) (stdnet.Conn, error) { return tracked, nil }})
	defer internet.UseAlternativeSystemDialer(nil)
	go func() { _, _ = io.Copy(io.Discard, peer) }()
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	started := time.Now()
	conn, err := Dial(ctx, g2rayWSDest(), g2rayWSSettings("chrome"))
	if conn != nil {
		_ = conn.Close()
	}
	if err == nil || time.Since(started) > 2*time.Second || !tracked.closed.Load() {
		t.Fatalf("parent cancellation did not close the socket promptly: err=%v elapsed=%s closed=%v", err, time.Since(started), tracked.closed.Load())
	}
}

// A peer sends invalid TLS data without closing. The caller must close the
// dialed socket when the handshake fails, even if the parent is not cancelled.
func TestG2RayWSFailedTLSHandshakeClosesSocket(t *testing.T) {
	raw, peer := stdnet.Pipe()
	tracked := &g2rayObservedConn{Conn: raw}
	defer raw.Close()
	defer peer.Close()
	internet.UseAlternativeSystemDialer(&g2rayFakeDialer{dial: func(context.Context) (stdnet.Conn, error) { return tracked, nil }})
	defer internet.UseAlternativeSystemDialer(nil)
	go func() {
		b := make([]byte, 32*1024)
		_, _ = peer.Read(b)
		_, _ = peer.Write([]byte("HTTP/1.1 400 Bad Request\r\n\r\n"))
		_, _ = io.Copy(io.Discard, peer)
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := Dial(ctx, g2rayWSDest(), g2rayWSSettings("chrome"))
	if conn != nil {
		_ = conn.Close()
	}
	if err == nil {
		t.Fatal("invalid TLS response unexpectedly succeeded")
	}
	if !tracked.closed.Load() {
		t.Fatal("TLS handshake failed but dialed socket was left open")
	}
}
