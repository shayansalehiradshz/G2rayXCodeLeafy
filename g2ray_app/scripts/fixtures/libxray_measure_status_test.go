package nodep

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestG2RayMeasureStatusPolicy(t *testing.T) {
	for _, test := range []struct {
		name string
		path string
		code int
		ok   bool
	}{
		{"expected_204", "/generate_204?sample=1", 204, true},
		{"wrong_200_on_204", "/generate_204?sample=1", 200, false},
		{"wrong_201_on_204", "/generate_204", 201, false},
		{"ordinary_200", "/", 200, true},
		{"ordinary_204", "/", 204, true},
		{"forbidden", "/generate_204", 403, false},
		{"server_error", "/generate_204", 500, false},
		{"unavailable", "/generate_204", 503, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodHead {
					t.Errorf("unexpected probe method %s", r.Method)
				}
				w.WriteHeader(test.code)
			}))
			defer server.Close()
			delay, err := MeasureDelay(1, server.URL+test.path, "")
			if (err == nil) != test.ok {
				t.Fatalf("HTTP %d at %s: delay=%d err=%v, want success=%v", test.code, test.path, delay, err, test.ok)
			}
			if !test.ok && delay != PingDelayError {
				t.Fatalf("unexpected failure delay %d", delay)
			}
		})
	}
}

func TestG2RayMeasureRejectsRedirectWithoutContactingTarget(t *testing.T) {
	targetRequests := 0
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		targetRequests++
		w.WriteHeader(http.StatusOK)
	}))
	defer target.Close()
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL, http.StatusFound)
	}))
	defer redirect.Close()
	if _, err := MeasureDelay(1, redirect.URL, ""); err == nil {
		t.Fatal("redirect was accepted as a successful route")
	}
	if targetRequests != 0 {
		t.Fatal("measurement followed a redirect")
	}
}

type g2rayCancelledTransport struct{}

func (g2rayCancelledTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return nil, context.Canceled
}

func TestG2RayMeasurePreservesCancellation(t *testing.T) {
	client := &http.Client{Transport: g2rayCancelledTransport{}}
	if _, err := PingHTTPRequest(client, "http://example.invalid/generate_204", 1); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancellation was not preserved: %v", err)
	}
}

func TestG2RayMeasureTimeoutIsNotSuccess(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer server.Close()
	client, err := CoreHTTPClient(30*time.Millisecond, "")
	if err != nil {
		t.Fatal(err)
	}
	defer client.CloseIdleConnections()
	if _, err := PingHTTPRequest(client, server.URL, 1); err == nil {
		t.Fatal("timeout was accepted as a successful route")
	}
}

func TestG2RaySharedProbeRejectsRedirectsFromBatchStyleClients(t *testing.T) {
	var targetRequests, originalPolicyCalls atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		targetRequests.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer target.Close()
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL, http.StatusFound)
	}))
	defer redirect.Close()
	client := &http.Client{Timeout: time.Second, CheckRedirect: func(*http.Request, []*http.Request) error {
		originalPolicyCalls.Add(1)
		return nil
	}}
	defer client.CloseIdleConnections()
	if _, err := PingHTTPRequest(client, redirect.URL+"/generate_204", 1); err == nil {
		t.Fatal("a custom/batch HTTP client accepted a redirected 204")
	}
	if targetRequests.Load() != 0 || originalPolicyCalls.Load() != 0 {
		t.Fatal("the probe followed a redirect")
	}
	response, err := client.Get(redirect.URL)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if targetRequests.Load() != 1 || originalPolicyCalls.Load() != 1 {
		t.Fatal("the probe mutated its caller's redirect policy")
	}
}
