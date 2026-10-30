package splithttp

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/http/httptrace"
	"net/url"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/buf"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
	xtls "github.com/xtls/xray-core/transport/internet/tls"
	"golang.org/x/net/http2"
)

type g2rayRequestTransport func(*http.Request) (*http.Response, error)

func (f g2rayRequestTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestG2RayXHTTPRequestCancellationBeforeConnection(t *testing.T) {
	started, stopped := make(chan struct{}), make(chan struct{})
	c := &DefaultDialerClient{transportConfig: &Config{}, client: &http.Client{
		Transport: g2rayRequestTransport(func(r *http.Request) (*http.Response, error) {
			close(started)
			<-r.Context().Done()
			close(stopped)
			return nil, r.Context().Err()
		}),
	}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan error, 1)
	go func() { _, _, _, err := c.OpenStream(ctx, "https://example.invalid/", "", nil, false); result <- err }()
	<-started
	cancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) { t.Fatalf("wrong cancellation: %v", err) }
	case <-time.After(time.Second): t.Fatal("obsolete caller still waiting")
	}
	select { case <-stopped: case <-time.After(time.Second): t.Fatal("request not canceled") }
	if c.IsClosed() { t.Fatal("one canceled request poisoned the shared client") }
}

func TestG2RayXHTTPCloseBeforeHeadersCancelsRequest(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	stopped := make(chan struct{})
	c := &DefaultDialerClient{transportConfig: &Config{}, client: &http.Client{
		Transport: g2rayRequestTransport(func(r *http.Request) (*http.Response, error) {
			httptrace.ContextClientTrace(r.Context()).GotConn(httptrace.GotConnInfo{Conn: a})
			<-r.Context().Done()
			close(stopped)
			return nil, r.Context().Err()
		}),
	}}
	r, _, _, err := c.OpenStream(context.Background(), "https://example.invalid/", "", nil, false)
	if err != nil { t.Fatal(err) }
	if err := r.Close(); err != nil { t.Fatal(err) }
	select { case <-stopped: case <-time.After(time.Second): t.Fatal("Close retained the pending HTTP request") }
	if c.IsClosed() { t.Fatal("closing one stream poisoned the pool") }
}

func TestG2RayXHTTPSetupFailureReturnsError(t *testing.T) {
	want := errors.New("owned dial failure")
	c := &DefaultDialerClient{transportConfig: &Config{}, client: &http.Client{
		Transport: g2rayRequestTransport(func(*http.Request) (*http.Response, error) { return nil, want }),
	}}
	r, _, _, err := c.OpenStream(context.Background(), "https://example.invalid/", "", nil, false)
	if r != nil || !errors.Is(err, want) { t.Fatalf("stream=%v error=%v", r, err) }
}

func TestG2RayXHTTPPacketCancellationDoesNotPoisonPool(t *testing.T) {
	started, stopped := make(chan struct{}), make(chan struct{})
	c := &DefaultDialerClient{transportConfig: &Config{}, httpVersion: "2", client: &http.Client{
		Transport: g2rayRequestTransport(func(r *http.Request) (*http.Response, error) {
			close(started)
			<-r.Context().Done()
			close(stopped)
			return nil, r.Context().Err()
		}),
	}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan error, 1)
	go func() { result <- c.PostPacket(ctx, "https://example.invalid/", "test", "0", nil) }()
	<-started
	cancel()
	select { case err := <-result:
		if !errors.Is(err, context.Canceled) { t.Fatal(err) }
	case <-time.After(time.Second): t.Fatal("packet upload ignored cancellation") }
	<-stopped
	if c.IsClosed() { t.Fatal("canceled packet poisoned pool") }
}

func TestG2RayXHTTPPhysicalTLSHonorsEarlierDeadline(t *testing.T) {
	for _, fp := range []string{"chrome", "unsafe"} {
		t.Run(fp, func(t *testing.T) {
			raw, peer := net.Pipe()
			defer raw.Close()
			defer peer.Close()
			tracked := &g2rayLeakConn{Conn: raw}
			internet.UseAlternativeSystemDialer(&g2rayLeakDialer{conn: tracked})
			defer internet.UseAlternativeSystemDialer(nil)
			go io.Copy(io.Discard, peer)
			settings := &internet.MemoryStreamConfig{ProtocolName: "splithttp", ProtocolSettings: &Config{},
				SecurityType: "tls", SecuritySettings: &xtls.Config{ServerName: "example.com", Fingerprint: fp, NextProtocol: []string{"h2"}}}
			c := createHTTPClient(xnet.TCPDestination(xnet.DomainAddress("example.com"), 443), settings).(*DefaultDialerClient)
			defer c.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
			defer cancel()
			at := time.Now()
			conn, err := c.client.Transport.(*http2.Transport).DialTLSContext(ctx, "tcp", "example.com:443", &tls.Config{})
			if conn != nil { conn.Close(); t.Fatal("silent peer established TLS") }
			if err == nil || time.Since(at) > 500*time.Millisecond || !tracked.closed.Load() {
				t.Fatalf("deadline/cleanup: error=%v duration=%s closed=%v", err, time.Since(at), tracked.closed.Load())
			}
		})
	}
}

func TestG2RayXHTTPPhysicalTLSHasDefaultSetupBudget(t *testing.T) {
	raw, peer := net.Pipe()
	defer raw.Close()
	defer peer.Close()
	tracked := &g2rayLeakConn{Conn: raw}
	internet.UseAlternativeSystemDialer(&g2rayLeakDialer{conn: tracked})
	defer internet.UseAlternativeSystemDialer(nil)
	go io.Copy(io.Discard, peer)
	settings := &internet.MemoryStreamConfig{ProtocolName: "splithttp", ProtocolSettings: &Config{},
		SecurityType: "tls", SecuritySettings: &xtls.Config{ServerName: "example.com", Fingerprint: "chrome", NextProtocol: []string{"h2"}}}
	c := createHTTPClient(xnet.TCPDestination(xnet.DomainAddress("example.com"), 443), settings).(*DefaultDialerClient)
	defer c.Close()
	at := time.Now()
	conn, err := c.client.Transport.(*http2.Transport).DialTLSContext(context.Background(), "tcp", "example.com:443", &tls.Config{})
	if conn != nil { conn.Close(); t.Fatal("silent peer established TLS") }
	if elapsed := time.Since(at); err == nil || elapsed < 7500*time.Millisecond || elapsed > 9500*time.Millisecond || !tracked.closed.Load() {
		t.Fatalf("setup budget: error=%v duration=%s closed=%v", err, elapsed, tracked.closed.Load())
	}
}

// Actual shared HTTP/2 transport: close one stream while another keeps working.
// Parent setup cancellation after GotConn must not kill established requests.
func TestG2RayXHTTPSharedH2StreamOwnership(t *testing.T) {
	var accepted atomic.Int32
	var wg sync.WaitGroup
	s := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		wg.Add(1)
		defer wg.Done()
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		io.WriteString(w, "ready")
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	s.EnableHTTP2 = true
	s.Config.ConnState = func(_ net.Conn, state http.ConnState) { if state == http.StateNew { accepted.Add(1) } }
	s.StartTLS()
	defer s.Close()
	u, _ := url.Parse(s.URL)
	host, port, _ := net.SplitHostPort(u.Host)
	p, _ := strconv.Atoi(port)
	certHash := sha256.Sum256(s.Certificate().Raw)
	settings := &internet.MemoryStreamConfig{ProtocolName: "splithttp", ProtocolSettings: &Config{},
		SecurityType: "tls", SecuritySettings: &xtls.Config{PinnedPeerCertSha256: [][]byte{certHash[:]}, NextProtocol: []string{"h2"}}}
	c := createHTTPClient(xnet.TCPDestination(xnet.IPAddress(net.ParseIP(host)), xnet.Port(p)), settings).(*DefaultDialerClient)
	defer c.Close()
	ctx, cancel := context.WithCancel(context.Background())
	first, _, _, err := c.OpenStream(ctx, s.URL, "", nil, false)
	if err != nil { t.Fatal(err) }
	defer first.Close()
	second, _, _, err := c.OpenStream(context.Background(), s.URL, "", nil, false)
	if err != nil { t.Fatal(err) }
	defer second.Close()
	cancel()
	for _, r := range []io.Reader{first, second} {
		b := make([]byte, 5)
		if _, err := io.ReadFull(r, b); err != nil || string(b) != "ready" { t.Fatalf("established stream interrupted: %q %v", b, err) }
	}
	first.Close()
	third, _, _, err := c.OpenStream(context.Background(), s.URL, "", nil, false)
	if err != nil { t.Fatal(err) }
	b := make([]byte, 5)
	if _, err := io.ReadFull(third, b); err != nil || string(b) != "ready" { t.Fatalf("reused stream failed: %v", err) }
	third.Close()
	second.Close()
	wg.Wait()
	if accepted.Load() != 1 { t.Fatalf("expected one shared physical connection, got %d", accepted.Load()) }
}

type g2rayStreamClient struct{ reads []*g2rayReadCloser }
func (*g2rayStreamClient) IsClosed() bool { return false }
func (c *g2rayStreamClient) OpenStream(context.Context, string, string, io.Reader, bool) (io.ReadCloser, xnet.Addr, xnet.Addr, error) {
	r := &g2rayReadCloser{}
	c.reads = append(c.reads, r)
	return r, &net.TCPAddr{}, &net.TCPAddr{}, nil
}
func (*g2rayStreamClient) PostPacket(context.Context, string, string, string, buf.MultiBuffer) error { return nil }

func TestG2RayXHTTPStreamUpCloseOwnsBothRequests(t *testing.T) {
	c := &g2rayStreamClient{}
	dest := xnet.TCPDestination(xnet.DomainAddress("example.invalid"), 80)
	settings := &internet.MemoryStreamConfig{ProtocolName: "splithttp", ProtocolSettings: &Config{Mode: "stream-up"}}
	key := dialerConf{dest, settings}
	manager := NewXmuxManager(XmuxConfig{MaxConnections:&RangeConfig{From:1, To:1}}, func() XmuxConn { return c })
	globalDialerAccess.Lock()
	if globalDialerMap == nil { globalDialerMap = make(map[dialerConf]*XmuxManager) }
	globalDialerMap[key] = manager
	globalDialerAccess.Unlock()
	defer func(){ globalDialerAccess.Lock(); delete(globalDialerMap, key); globalDialerAccess.Unlock() }()
	conn, err := Dial(context.Background(), dest, settings)
	if err != nil { t.Fatal(err) }
	if len(c.reads) != 2 { t.Fatalf("expected up/down requests, got %d", len(c.reads)) }
	conn.Close()
	for i, r := range c.reads { if r.closes.Load() != 1 { t.Fatalf("request %d was not closed", i) } }
	if manager.xmuxClients[0].Running.Load() != 0 { t.Fatal("retained running-stream reference") }
}

func TestG2RayXHTTPCanceledPoolWaiterLeavesNextRequestUsable(t *testing.T) {
	s := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		io.WriteString(w, "ready")
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	s.EnableHTTP2 = true
	s.StartTLS()
	defer s.Close()
	u, _ := url.Parse(s.URL)
	host, port, _ := net.SplitHostPort(u.Host)
	p, _ := strconv.Atoi(port)
	certHash := sha256.Sum256(s.Certificate().Raw)
	settings := &internet.MemoryStreamConfig{ProtocolName: "splithttp", ProtocolSettings: &Config{},
		SecurityType: "tls", SecuritySettings: &xtls.Config{PinnedPeerCertSha256: [][]byte{certHash[:]}, NextProtocol: []string{"h2"}}}
	c := createHTTPClient(xnet.TCPDestination(xnet.IPAddress(net.ParseIP(host)), xnet.Port(p)), settings).(*DefaultDialerClient)
	defer c.Close()
	h2 := c.client.Transport.(*http2.Transport)
	original := h2.DialTLSContext
	started, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	var attempts atomic.Int32
	h2.DialTLSContext = func(ctx context.Context, network, addr string, cfg *tls.Config) (net.Conn, error) {
		attempts.Add(1)
		once.Do(func(){ close(started) })
		select {
		case <-release: return original(ctx, network, addr, cfg)
		case <-ctx.Done(): return nil, ctx.Err()
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	first := make(chan error, 1)
	go func(){ r, _, _, err := c.OpenStream(ctx, s.URL, "", nil, false); if r != nil { r.Close() }; first <- err }()
	<-started
	type result struct { r io.ReadCloser; err error }
	second := make(chan result, 1)
	go func(){ r, _, _, err := c.OpenStream(context.Background(), s.URL, "", nil, false); second <- result{r, err} }()
	cancel()
	select {
	case err := <-first: if !errors.Is(err, context.Canceled) { t.Fatalf("wrong error: %v", err) }
	case <-time.After(time.Second): t.Fatal("canceled pool waiter did not return")
	}
	close(release)
	select {
	case got := <-second:
		if got.err != nil { t.Fatal(got.err) }
		defer got.r.Close()
		b := make([]byte, 5)
		if _, err := io.ReadFull(got.r, b); err != nil || string(b) != "ready" { t.Fatalf("other waiter lost shared dial: %q %v", b, err) }
	case <-time.After(3*time.Second): t.Fatal("other pool waiter stayed blocked")
	}
	// The transport may discard an unneeded pending dial and create a new one;
	// that is not a lost active stream. The next request must still complete.
	if attempts.Load() > 2 || c.IsClosed() { t.Fatalf("pool ownership: attempts=%d closed=%v", attempts.Load(), c.IsClosed()) }
}
