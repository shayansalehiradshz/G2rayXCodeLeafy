package xray

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/libxray/nodep"
	"github.com/xtls/xray-core/app/observatory"
	"github.com/xtls/xray-core/features/extension"
)

func TestG2RayActualPingBatchStatusAndRedirectPolicy(t *testing.T) {
	path := filepath.Join(t.TempDir(), "direct.json")
	if err := os.WriteFile(path, []byte(`{"outbounds":[{"protocol":"freedom","tag":"proxy","settings":{}}]}`), 0600); err != nil {
		t.Fatal(err)
	}
	for _, code := range []int{204, 200, 403, 503, 302} {
		t.Run(http.StatusText(code), func(t *testing.T) {
			var targetRequests atomic.Int32
			target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				targetRequests.Add(1)
				w.WriteHeader(http.StatusNoContent)
			}))
			defer target.Close()
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if code == http.StatusFound {
					http.Redirect(w, r, target.URL+"/generate_204", code)
				} else {
					w.WriteHeader(code)
				}
			}))
			defer server.Close()
			results, err := PingBatch([]PingBatchItem{{ConfigPath: path, OutboundTag: "proxy"}}, 2, server.URL+"/generate_204")
			if err != nil || len(results) != 1 {
				t.Fatalf("batch failed before measuring: %v, results=%v", err, results)
			}
			if results[0].Success != (code == http.StatusNoContent) {
				t.Fatalf("HTTP %d produced wrong JNI-facing result: %+v", code, results[0])
			}
			if code != http.StatusNoContent && (results[0].Delay != nodep.PingDelayError || results[0].Error == "") {
				t.Fatalf("failed batch result lost its error mapping: %+v", results[0])
			}
			if targetRequests.Load() != 0 {
				t.Fatal("batch followed a redirect")
			}
		})
	}
}

func TestG2RayActualObserverCleanupAndDetachedResults(t *testing.T) {
	for _, code := range []int{204, 503, 0} {
		t.Run(fmt.Sprintf("status_%d", code), func(t *testing.T) {
			var connections atomic.Int32
			endpoint := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if code == 0 {
					<-r.Context().Done()
					return
				}
				w.WriteHeader(code)
			}))
			endpoint.Config.ConnState = func(_ net.Conn, state http.ConnState) {
				if state == http.StateNew {
					connections.Add(1)
				} else if state == http.StateClosed || state == http.StateHijacked {
					connections.Add(-1)
				}
			}
			endpoint.Start()
			defer endpoint.Close()
			config := fmt.Sprintf(`{"log":{"loglevel":"none"},"outbounds":[{"protocol":"freedom","tag":"proxy"}],"observatory":{"subjectSelector":["proxy"],"probeURL":%q,"probeInterval":"1h","enableConcurrency":true}}`, endpoint.URL+"/generate_204")
			instance, err := StartXrayFromJSON(config)
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			observer, ok := instance.GetFeature(extension.ObservatoryType()).(extension.Observatory)
			if !ok {
				t.Fatal("real core has no observer")
			}
			var result *observatory.ObservationResult
			deadline := time.Now().Add(12 * time.Second)
			for time.Now().Before(deadline) {
				message, err := observer.GetObservation(context.Background())
				if err != nil {
					t.Fatal(err)
				}
				result = message.(*observatory.ObservationResult)
				if len(result.Status) == 1 && result.Status[0].LastTryTime > 0 {
					break
				}
				time.Sleep(5 * time.Millisecond)
			}
			if result == nil || len(result.Status) != 1 || result.Status[0].Alive != (code == 204) {
				t.Fatalf("unexpected live observation for HTTP %d: %v", code, result)
			}
			result.Status[0].Alive = !result.Status[0].Alive
			message, err := observer.GetObservation(context.Background())
			if err != nil || message.(*observatory.ObservationResult).Status[0].Alive != (code == 204) {
				t.Fatal("observer exposed its mutable result")
			}
			deadline = time.Now().Add(8 * time.Second)
			for connections.Load() != 0 && time.Now().Before(deadline) {
				time.Sleep(10 * time.Millisecond)
			}
			if connections.Load() != 0 {
				t.Fatalf("probe left %d server connections open", connections.Load())
			}
		})
	}
}
