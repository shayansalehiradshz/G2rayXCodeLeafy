package splithttp

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"io"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/signal/done"
	"github.com/xtls/xray-core/transport/pipe"
)

// Exercise the same pipe ownership and short-write handling used by XHTTP.
// This is a stress test: the original bad result depends on scheduling.
func TestG2RayXHTTPUploadExactBytes(t *testing.T) {
	reader, writer := pipe.New(pipe.WithSizeLimit(512 * 1024))
	uw := uploadWriter{writer, 512 * 1024}
	type result struct {
		length int64
		digest [sha256.Size]byte
		err    error
	}
	results := make(chan result, 1)
	go func() {
		h := sha256.New()
		var total int64
		for {
			mb, err := reader.ReadMultiBuffer()
			if err != nil {
				var sum [sha256.Size]byte
				copy(sum[:], h.Sum(nil))
				if errors.Is(err, io.EOF) {
					err = nil
				}
				results <- result{total, sum, err}
				return
			}
			c := buf.MultiBufferContainer{MultiBuffer: mb}
			n, err := io.Copy(h, &c)
			total += n
			if err != nil {
				results <- result{err: err}
				return
			}
		}
	}()
	defer writer.Close()

	const iterations = 2500
	payload := make([]byte, 32*1024)
	expected := sha256.New()
	for i := 0; i < iterations; i++ {
		binary.LittleEndian.PutUint64(payload, uint64(i))
		if _, err := expected.Write(payload); err != nil {
			t.Fatal(err)
		}
		if err := buf.WriteAllBytes(uw, payload, nil); err != nil {
			t.Fatalf("write %d: %v", i, err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	got := <-results
	if got.err != nil {
		t.Fatal(got.err)
	}
	wantLength := int64(iterations * len(payload))
	var wantDigest [sha256.Size]byte
	copy(wantDigest[:], expected.Sum(nil))
	if got.length != wantLength || got.digest != wantDigest {
		t.Fatalf("stream mismatch: got %d bytes / %x, want %d bytes / %x", got.length, got.digest, wantLength, wantDigest)
	}
}

type g2rayFailingTransport struct{}

func (g2rayFailingTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return nil, errors.New("simulated transport failure")
}

// PostPacket marks the client closed asynchronously while mux selection reads it.
func TestG2RayXHTTPClosedConcurrent(t *testing.T) {
	c := &DefaultDialerClient{
		transportConfig: &Config{},
		client:          &http.Client{Transport: g2rayFailingTransport{}},
	}
	start := make(chan struct{})
	var wg sync.WaitGroup
	for worker := 0; worker < 4; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for i := 0; i < 1000; i++ {
				_ = c.PostPacket(context.Background(), "https://example.invalid/", "test", "1", nil)
			}
		}()
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		<-start
		for i := 0; i < 100000; i++ {
			_ = c.IsClosed()
		}
	}()
	close(start)
	wg.Wait()
	if !c.IsClosed() {
		t.Fatal("failed transport did not close client")
	}
}

type g2rayReadCloser struct{ closes atomic.Int32 }

func (*g2rayReadCloser) Read([]byte) (int, error) { return 0, io.EOF }
func (r *g2rayReadCloser) Close() error {
	r.closes.Add(1)
	return nil
}

// Compile after applying the XHTTP race patch; it uses the patched constructor.
func TestG2RayXHTTPWaitSetReadCloseConcurrent(t *testing.T) {
	for i := 0; i < 5000; i++ {
		w := &WaitReadCloser{wait: done.New()}
		body := &g2rayReadCloser{}
		start := make(chan struct{})
		var wg sync.WaitGroup
		wg.Add(3)
		go func() { defer wg.Done(); <-start; w.Set(body) }()
		go func() { defer wg.Done(); <-start; _, _ = w.Read(make([]byte, 1)) }()
		go func() { defer wg.Done(); <-start; _ = w.Close() }()
		close(start)
		wg.Wait()
		if err := w.Close(); err != nil {
			t.Fatalf("iteration %d: %v", i, err)
		}
		if got := body.closes.Load(); got != 1 {
			t.Fatalf("iteration %d: body closed %d times, want 1", i, got)
		}
	}
}

type g2rayCloseWriter struct{ err error }

func (g2rayCloseWriter) Write(b []byte) (int, error) { return len(b), nil }
func (w g2rayCloseWriter) Close() error              { return w.err }

type g2rayCloseReader struct{ err error }

func (g2rayCloseReader) Read([]byte) (int, error) { return 0, io.EOF }
func (r g2rayCloseReader) Close() error           { return r.err }

// Requires the separate, optional close-error patch; it does not test stalls.
func TestG2RayXHTTPCloseError(t *testing.T) {
	readErr := errors.New("read close")
	writeErr := errors.New("write close")
	for _, tc := range []struct {
		name string
		werr error
		rerr error
		want error
	}{
		{"none", nil, nil, nil},
		{"writer", writeErr, nil, writeErr},
		{"reader", nil, readErr, readErr},
		{"both", writeErr, readErr, writeErr},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c := &splitConn{writer: g2rayCloseWriter{tc.werr}, reader: g2rayCloseReader{tc.rerr}}
			if got := c.Close(); !errors.Is(got, tc.want) {
				t.Fatalf("close error = %v; want %v", got, tc.want)
			}
		})
	}
}
