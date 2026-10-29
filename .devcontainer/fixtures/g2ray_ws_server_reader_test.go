package websocket

import (
	"bufio"
	"bytes"
	"crypto/sha1"
	"encoding/base64"
	"errors"
	"fmt"
	gorillaws "github.com/gorilla/websocket"
	"io"
	stdnet "net"
	"net/http"
	"net/url"
	"sync/atomic"
	"testing"
	"testing/iotest"
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
