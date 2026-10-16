// Opt-in retained HTTP/2 acceptance through a separately started loopback SOCKS core.
package main

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/http/httptrace"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type endpoint struct {
	URL    string
	Status int
	Body   string
}

type connectionEvent struct {
	ID     int   `json:"id"`
	Reused bool  `json:"reused"`
	IdleMs int64 `json:"idle_ms"`
}

type probeResult struct {
	Phase       string            `json:"phase"`
	Endpoint    string            `json:"endpoint"`
	Status      int               `json:"status"`
	Protocol    string            `json:"protocol"`
	Usable      bool              `json:"usable"`
	ElapsedMs   int64             `json:"elapsed_ms"`
	FirstByteMs *int64            `json:"ttfb_ms,omitempty"`
	Error       string            `json:"error,omitempty"`
	Connections []connectionEvent `json:"connections"`
}

type connectionTracker struct {
	mu  sync.Mutex
	ids map[net.Conn]int
}

func (t *connectionTracker) event(info httptrace.GotConnInfo) connectionEvent {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.ids == nil {
		t.ids = make(map[net.Conn]int)
	}
	id, found := t.ids[info.Conn]
	if !found {
		id = len(t.ids) + 1
		t.ids[info.Conn] = id
	}
	return connectionEvent{ID: id, Reused: info.Reused, IdleMs: info.IdleTime.Milliseconds()}
}

func probe(client *http.Client, tracker *connectionTracker, target endpoint, phase string) probeResult {
	return probeRequest(client, tracker, target, phase, http.MethodGet, nil)
}

func probeRequest(client *http.Client, tracker *connectionTracker, target endpoint, phase, method string, body io.Reader) probeResult {
	result := probeResult{Phase: phase, Endpoint: target.URL}
	request, err := http.NewRequest(method, target.URL, body)
	if err != nil {
		result.Error = err.Error()
		return result
	}
	request.Header.Set("Cache-Control", "no-cache")
	if method == http.MethodPost {
		request.Header.Set("Content-Type", "application/json")
	}
	var eventsMu sync.Mutex
	var events []connectionEvent
	var firstByteMs *int64
	startedAt := time.Now()
	trace := &httptrace.ClientTrace{
		GotConn: func(info httptrace.GotConnInfo) {
			event := tracker.event(info)
			eventsMu.Lock()
			events = append(events, event)
			eventsMu.Unlock()
		},
		GotFirstResponseByte: func() {
			elapsed := time.Since(startedAt).Milliseconds()
			eventsMu.Lock()
			if firstByteMs == nil {
				firstByteMs = &elapsed
			}
			eventsMu.Unlock()
		},
	}
	request = request.WithContext(httptrace.WithClientTrace(request.Context(), trace))
	response, err := client.Do(request)
	if err == nil {
		result.Status, result.Protocol = response.StatusCode, response.Proto
		body, readError := io.ReadAll(io.LimitReader(response.Body, 256*1024+1))
		response.Body.Close()
		switch {
		case readError != nil:
			err = readError
		case len(body) > 256*1024:
			err = fmt.Errorf("health response exceeds 256 KiB")
		case response.ProtoMajor != 2:
			err = fmt.Errorf("endpoint did not negotiate HTTP/2")
		case response.StatusCode != target.Status || !strings.Contains(string(body), target.Body):
			err = fmt.Errorf("unexpected health response")
		default:
			result.Usable = true
		}
	}
	result.ElapsedMs = time.Since(startedAt).Milliseconds()
	if err != nil {
		result.Error = err.Error()
	}
	eventsMu.Lock()
	result.Connections = append([]connectionEvent(nil), events...)
	result.FirstByteMs = firstByteMs
	eventsMu.Unlock()
	return result
}

const (
	streamFixturePath  = "/g2ray-fixture-stream"
	streamFixtureInput = `{"prompt":"owned delayed-response fixture"}`
	streamFixtureFirst = "data: first\n\n"
	streamFixtureFinal = "data: complete\n\n"
)

func loopbackHandler(gap time.Duration) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path != streamFixturePath {
			writer.WriteHeader(http.StatusNoContent)
			return
		}
		if request.Method != http.MethodPost || request.ProtoMajor != 2 {
			http.Error(writer, "expected HTTP/2 POST", http.StatusBadRequest)
			return
		}
		body, err := io.ReadAll(io.LimitReader(request.Body, int64(len(streamFixtureInput)+1)))
		if err != nil || string(body) != streamFixtureInput {
			http.Error(writer, "incomplete request body", http.StatusBadRequest)
			return
		}
		// Request-body EOF (HTTP/2 END_STREAM) is not TCP EOF. Keep both a
		// delayed first response and an idle streaming gap beyond 2/5 seconds.
		wait := func() bool {
			timer := time.NewTimer(gap)
			defer timer.Stop()
			select {
			case <-request.Context().Done():
				return false
			case <-timer.C:
				return true
			}
		}
		if !wait() {
			return
		}
		writer.Header().Set("Content-Type", "text/event-stream")
		if _, err := io.WriteString(writer, streamFixtureFirst); err != nil {
			return
		}
		writer.(http.Flusher).Flush()
		if !wait() {
			return
		}
		io.WriteString(writer, streamFixtureFinal)
	}
}

func newTransport(socksAddress string) *http.Transport {
	transport := &http.Transport{
		ForceAttemptHTTP2:   true,
		DialContext:         (&net.Dialer{Timeout: 10 * time.Second}).DialContext,
		TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 20 * time.Second,
		MaxIdleConns: 6, MaxIdleConnsPerHost: 2, IdleConnTimeout: 10 * time.Minute,
	}
	if socksAddress != "" {
		transport.Proxy = http.ProxyURL(&url.URL{Scheme: "socks5", Host: socksAddress})
	}
	return transport
}

func clientFor(transport *http.Transport) *http.Client {
	return &http.Client{
		Transport: transport, Timeout: 30 * time.Second,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse },
	}
}

func responsiveQuorum(results []probeResult) bool {
	successes := 0
	for _, result := range results {
		if result.Usable && result.ElapsedMs <= 6000 {
			successes++
		}
	}
	return successes >= 2
}

func parseLoopbackPorts(value string) ([]int, error) {
	if value == "" {
		return nil, nil
	}
	parts := strings.Split(value, ",")
	if len(parts) != 3 {
		return nil, fmt.Errorf("owned loopback fixture requires exactly three ports")
	}
	var ports []int
	seen := make(map[int]bool)
	for _, part := range parts {
		port, err := strconv.Atoi(part)
		if err != nil || port < 1024 || port > 65535 || seen[port] {
			return nil, fmt.Errorf("owned loopback fixture requires distinct non-privileged ports")
		}
		ports = append(ports, port)
		seen[port] = true
	}
	return ports, nil
}

func loopbackTargets(ports []int) ([]endpoint, *x509.CertPool, func(), error) {
	roots := x509.NewCertPool()
	var servers []*httptest.Server
	var targets []endpoint
	cleanup := func() {
		for _, server := range servers {
			server.Close()
		}
	}
	if len(ports) != 0 && len(ports) != 3 {
		return nil, nil, cleanup, fmt.Errorf("owned loopback fixture requires three ports")
	}
	for index := range 3 {
		server := httptest.NewUnstartedServer(loopbackHandler(7 * time.Second))
		if len(ports) != 0 {
			server.Listener.Close()
			listener, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", fmt.Sprint(ports[index])))
			if err != nil {
				cleanup()
				return nil, nil, cleanup, fmt.Errorf("owned loopback origin did not bind: %w", err)
			}
			server.Listener = listener
		}
		server.EnableHTTP2 = true
		server.StartTLS()
		servers = append(servers, server)
		roots.AddCert(server.Certificate())
		targets = append(targets, endpoint{server.URL, http.StatusNoContent, ""})
	}
	return targets, roots, cleanup, nil
}

func run(socksAddress string, idle time.Duration, loopbackBaseline bool, originPorts []int) error {
	targets := []endpoint{
		{"https://www.gstatic.com/generate_204", http.StatusNoContent, ""},
		{"https://cp.cloudflare.com/generate_204", http.StatusNoContent, ""},
		{"https://example.com/", http.StatusOK, "Example Domain"},
	}
	var roots *x509.CertPool
	if loopbackBaseline {
		var cleanup func()
		var err error
		targets, roots, cleanup, err = loopbackTargets(originPorts)
		if err != nil {
			return err
		}
		defer cleanup()
		fmt.Println("fixture_scope=loopback_only; not Internet or public-edge acceptance")
	}
	makeTransport := func() *http.Transport {
		transport := newTransport(socksAddress)
		if roots != nil {
			transport.TLSClientConfig = &tls.Config{RootCAs: roots}
		}
		return transport
	}
	tracker := &connectionTracker{}
	transport := makeTransport()
	defer transport.CloseIdleConnections()
	client := clientFor(transport)
	phase := func(client *http.Client, name string) bool {
		results := make([]probeResult, 0, len(targets))
		for _, target := range targets {
			result := probe(client, tracker, target, name)
			json.NewEncoder(os.Stdout).Encode(result)
			results = append(results, result)
		}
		return responsiveQuorum(results)
	}
	if !phase(client, "initial") {
		return fmt.Errorf("initial HTTP/2 quorum failed; idle was not tested")
	}
	startedIdle := time.Now()
	time.Sleep(idle)
	fmt.Printf("idle_gap_ms=%d\n", time.Since(startedIdle).Milliseconds())
	retained := phase(client, "retained_pool")
	freshTransport := makeTransport()
	defer freshTransport.CloseIdleConnections()
	fresh := phase(clientFor(freshTransport), "fresh_socket")
	if !retained || !fresh {
		return fmt.Errorf("HTTP/2 quorum failed: retained=%t fresh=%t", retained, fresh)
	}
	if loopbackBaseline {
		target := endpoint{targets[0].URL + streamFixturePath, http.StatusOK, streamFixtureFirst + streamFixtureFinal}
		result := probeRequest(client, tracker, target, "delayed_post_stream", http.MethodPost, strings.NewReader(streamFixtureInput))
		json.NewEncoder(os.Stdout).Encode(result)
		if !result.Usable {
			return fmt.Errorf("delayed HTTP/2 POST/stream failed: %s", result.Error)
		}
	}
	return nil
}

func main() {
	port := flag.Int("socks-port", 0, "Port of the separately started loopback Xray SOCKS listener")
	direct := flag.Bool("direct-baseline", false, "Diagnostic baseline on this host, without Xray, SOCKS, or an environment proxy")
	loopback := flag.Bool("loopback-baseline", false, "Use three owned TLS/HTTP2 loopback servers to isolate forwarding from Internet egress")
	loopbackPortList := flag.String("loopback-origin-ports", "", "Three owned origin ports for a loopback-only server/client fixture")
	idleSeconds := flag.Int("idle-seconds", 70, "Idle interval between initial and retained requests (0-600)")
	flag.Parse()
	originPorts, portError := parseLoopbackPorts(*loopbackPortList)
	if (!*direct && (*port < 1 || *port > 65535)) || (*direct && *port != 0) ||
		*idleSeconds < 0 || *idleSeconds > 600 || flag.NArg() != 0 || portError != nil ||
		(*loopbackPortList != "" && !*loopback) {
		fmt.Fprintln(os.Stderr, "Use a valid --socks-port and --idle-seconds from 0 to 600")
		os.Exit(2)
	}
	address := net.JoinHostPort("127.0.0.1", fmt.Sprint(*port))
	if *direct {
		address = ""
	}
	if err := run(address, time.Duration(*idleSeconds)*time.Second, *loopback, originPorts); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
