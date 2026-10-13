package main

import (
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func newHTTP2Server(t *testing.T, handler http.HandlerFunc) *httptest.Server {
	t.Helper()
	server := httptest.NewUnstartedServer(handler)
	server.EnableHTTP2 = true
	server.StartTLS()
	t.Cleanup(server.Close)
	return server
}

func TestProbeTracesActualRetainedHTTP2Connection(t *testing.T) {
	server := newHTTP2Server(t, func(writer http.ResponseWriter, request *http.Request) {
		writer.WriteHeader(http.StatusNoContent)
	})
	client := server.Client()
	tracker := &connectionTracker{}
	target := endpoint{server.URL, http.StatusNoContent, ""}
	first := probe(client, tracker, target, "initial")
	second := probe(client, tracker, target, "retained")
	if !first.Usable || !second.Usable || first.Protocol != "HTTP/2.0" || len(first.Connections) != 1 || len(second.Connections) != 1 {
		t.Fatalf("Expected genuine HTTP/2 requests, got %+v / %+v", first, second)
	}
	if first.Connections[0].ID != second.Connections[0].ID || !second.Connections[0].Reused {
		t.Fatalf("No retained connection was measured: %+v / %+v", first, second)
	}
	client.CloseIdleConnections()
	third := probe(client, tracker, target, "fresh")
	if !third.Usable || len(third.Connections) != 1 || third.Connections[0].Reused || third.Connections[0].ID == second.Connections[0].ID {
		t.Fatalf("The test could not distinguish a fresh socket: %+v", third)
	}
}

func TestProbeRejectsWrongStatusBodyAndOversizedResponses(t *testing.T) {
	cases := []struct {
		name string
		code int
		body string
	}{
		{"wrong status", http.StatusNotFound, "Example Domain"},
		{"wrong body", http.StatusOK, "unexpected content"},
		{"oversized", http.StatusOK, string(make([]byte, 256*1024+1))},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			server := newHTTP2Server(t, func(writer http.ResponseWriter, request *http.Request) {
				writer.WriteHeader(test.code)
				writer.Write([]byte(test.body))
			})
			result := probe(server.Client(), &connectionTracker{}, endpoint{server.URL, http.StatusOK, "Example Domain"}, "test")
			if result.Usable || result.Error == "" {
				t.Fatalf("Accepted an invalid response: %+v", result)
			}
		})
	}
}

func TestQuorumRetainsTheSixSecondBoundary(t *testing.T) {
	results := []probeResult{{Usable: true, ElapsedMs: 6000}, {Usable: true, ElapsedMs: 6001}, {Usable: false, ElapsedMs: 1}}
	if responsiveQuorum(results) {
		t.Fatal("A slow or failed request was counted as responsive")
	}
	results[1].ElapsedMs = 1200
	if !responsiveQuorum(results) {
		t.Fatal("Two timely valid requests should satisfy the quorum")
	}
}

func TestProductionClientPreservesProxyTLSAndIdleSemantics(t *testing.T) {
	transport := newTransport("127.0.0.1:19090")
	request, err := http.NewRequest(http.MethodGet, "https://example.com/", nil)
	if err != nil {
		t.Fatal(err)
	}
	proxy, err := transport.Proxy(request)
	if err != nil || proxy.String() != "socks5://127.0.0.1:19090" {
		t.Fatalf("Expected the isolated SOCKS path, got %v / %v", proxy, err)
	}
	if !transport.ForceAttemptHTTP2 || transport.DisableKeepAlives || transport.IdleConnTimeout != 10*time.Minute {
		t.Fatal("Retained HTTP/2 semantics changed")
	}
	if transport.TLSClientConfig != nil && transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("Live validation must verify certificates")
	}
	client := clientFor(transport)
	if client.CheckRedirect(nil, nil) != http.ErrUseLastResponse {
		t.Fatal("A redirect must not become false route success")
	}
}

func TestDirectBaselineDoesNotUseAnEnvironmentOrSocksProxy(t *testing.T) {
	t.Setenv("HTTPS_PROXY", "https://proxy.invalid")
	if newTransport("").Proxy != nil {
		t.Fatal("Direct baseline accidentally used a proxy")
	}
}

func TestLoopbackBaselineVerifiesItsOwnTLSWithoutInternet(t *testing.T) {
	targets, roots, cleanup, err := loopbackTargets(nil)
	if err != nil {
		t.Fatal(err)
	}
	defer cleanup()
	transport := newTransport("")
	transport.TLSClientConfig = &tls.Config{RootCAs: roots}
	defer transport.CloseIdleConnections()
	client := clientFor(transport)
	tracker := &connectionTracker{}
	if len(targets) != 3 {
		t.Fatal("Loopback baseline needs three separately owned targets")
	}
	for _, target := range targets {
		first := probe(client, tracker, target, "initial")
		second := probe(client, tracker, target, "retained")
		if !first.Usable || !second.Usable || len(second.Connections) != 1 || !second.Connections[0].Reused {
			t.Fatalf("Loopback TLS/reuse fixture failed: %+v / %+v", first, second)
		}
	}
}

func TestLoopbackPortListRejectsDuplicatesAndInvalidDestinations(t *testing.T) {
	for _, value := range []string{"12345", "12345,12346", "12345,12345,12347", "443,12346,12347", "12345,no,12347", "12345,12346,65536"} {
		if _, err := parseLoopbackPorts(value); err == nil {
			t.Fatalf("Accepted invalid owned ports %q", value)
		}
	}
	ports, err := parseLoopbackPorts("12345,12346,12347")
	if err != nil || len(ports) != 3 || ports[2] != 12347 {
		t.Fatalf("Rejected valid owned ports: %v / %v", ports, err)
	}
}

func TestLoopbackOriginBindFailureReapsPreviouslyStartedOrigins(t *testing.T) {
	var listeners []net.Listener
	var ports []int
	for range 3 {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { listener.Close() })
		listeners = append(listeners, listener)
		ports = append(ports, listener.Addr().(*net.TCPAddr).Port)
	}
	listeners[0].Close()
	_, _, cleanup, err := loopbackTargets(ports)
	defer cleanup()
	if err == nil {
		t.Fatal("Accepted an occupied fixture origin port")
	}
	listener, bindError := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", ports[0]))
	if bindError != nil {
		t.Fatalf("A previously started owned origin leaked its listener: %v", bindError)
	}
	listener.Close()
}
