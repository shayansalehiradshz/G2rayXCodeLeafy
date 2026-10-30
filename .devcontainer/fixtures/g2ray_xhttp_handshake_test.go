package splithttp

import (
	"errors"
	"io"
	"sync"
	"testing"
	"time"
)

func TestG2RayXHTTPHandshakeTimeoutClosesOnlyOwnSession(t *testing.T) {
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	c := &splitConn{reader: reader, writer: writer}
	stop := c.StartHandshakeTimeout(30 * time.Millisecond)
	defer stop()
	done := make(chan error, 1)
	go func() { _, err := c.Read(make([]byte, 1)); done <- err }()
	select {
	case err := <-done:
		if err == nil { t.Fatal("timed out session returned no error") }
	case <-time.After(time.Second): t.Fatal("handshake session remained blocked")
	}
}

func TestG2RayXHTTPHandshakeGuardClearsBeforeEstablishedTraffic(t *testing.T) {
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	c := &splitConn{reader: reader, writer: writer}
	stop := c.StartHandshakeTimeout(30 * time.Millisecond)
	stop()
	time.Sleep(60 * time.Millisecond)
	go writer.Write([]byte("payload"))
	b := make([]byte, 7)
	if _, err := io.ReadFull(c, b); err != nil || string(b) != "payload" {
		t.Fatalf("cleared handshake timer interrupted established stream: %q %v", b, err)
	}
}

func TestG2RayXHTTPHandshakeGuardConcurrentStop(t *testing.T) {
	for i := 0; i < 100; i++ {
		reader, writer := io.Pipe()
		c := &splitConn{reader: reader, writer: writer}
		stop := c.StartHandshakeTimeout(time.Microsecond)
		var wg sync.WaitGroup
		for j := 0; j < 4; j++ { wg.Add(1); go func() { defer wg.Done(); stop() }() }
		wg.Wait()
		c.Close()
	}
}

type g2rayGuardWriter struct{ err error }
func (g2rayGuardWriter) Write(b []byte) (int, error) { return len(b), nil }
func (w g2rayGuardWriter) Close() error { return w.err }
type g2rayGuardReader struct{ err error }
func (g2rayGuardReader) Read([]byte) (int, error) { return 0, io.EOF }
func (r g2rayGuardReader) Close() error { return r.err }

func TestG2RayXHTTPServerCloseError(t *testing.T) {
	rerr, werr := errors.New("reader"), errors.New("writer")
	for _, tc := range []struct{r, w, want error}{{nil,nil,nil},{rerr,nil,rerr},{nil,werr,werr},{rerr,werr,werr}} {
		c := &splitConn{reader:g2rayGuardReader{tc.r}, writer:g2rayGuardWriter{tc.w}}
		if got := c.Close(); !errors.Is(got, tc.want) { t.Fatalf("got %v want %v", got, tc.want) }
	}
}
