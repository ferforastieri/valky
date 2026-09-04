package camera

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestProbeAndPTZAgainstFakeONVIFServer(t *testing.T) {
	var server *httptest.Server
	var ptzBody atomic.Value
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), "UsernameToken") {
			t.Error("request has no WS-Security UsernameToken")
		}
		switch {
		case strings.Contains(string(body), "GetCapabilities"):
			fmt.Fprintf(w, `<s:Envelope><s:Body><GetCapabilitiesResponse><Capabilities><Media><tt:XAddr>%s/onvif/media</tt:XAddr></Media><Events><tt:XAddr>%s/onvif/event_service</tt:XAddr></Events><PTZ/></Capabilities></GetCapabilitiesResponse></s:Body></s:Envelope>`, server.URL, server.URL)
		case strings.Contains(string(body), "GetProfiles"):
			fmt.Fprint(w, `<s:Envelope><s:Body><GetProfilesResponse><Profiles token="sub"><VideoEncoderConfiguration><Resolution><Width>640</Width><Height>360</Height></Resolution></VideoEncoderConfiguration></Profiles><Profiles token="main"><VideoEncoderConfiguration><Resolution><Width>1920</Width><Height>1080</Height></Resolution></VideoEncoderConfiguration><AudioEncoderConfiguration/><PTZConfiguration token="ptz-main"/></Profiles></GetProfilesResponse></s:Body></s:Envelope>`)
		case strings.Contains(string(body), "GetConfigurationOptions"):
			fmt.Fprint(w, `<s:Envelope><s:Body><GetConfigurationOptionsResponse><PTZConfigurationOptions><Spaces><ContinuousZoomVelocitySpace/></Spaces></PTZConfigurationOptions></GetConfigurationOptionsResponse></s:Body></s:Envelope>`)
		case strings.Contains(string(body), "ContinuousMove") || strings.Contains(string(body), "RelativeMove"):
			ptzBody.Store(string(body))
			fmt.Fprint(w, `<s:Envelope><s:Body><ContinuousMoveResponse/></s:Body></s:Envelope>`)
		default:
			http.Error(w, "unexpected request", http.StatusBadRequest)
		}
	}))
	defer server.Close()

	host, port := serverAddress(t, server.URL)
	client := NewONVIFClient()
	caps, profile, services, err := client.Probe(context.Background(), host, port, "camera", "secret")
	if err != nil {
		t.Fatal(err)
	}
	if profile != "main" || !caps.Events || !caps.PTZ || !caps.Audio || !caps.Zoom {
		t.Fatalf("unexpected probe result: profile=%q caps=%+v", profile, caps)
	}
	if services.Media == "" || services.Events == "" || services.PTZ == "" {
		t.Fatalf("service addresses were not retained: %+v", services)
	}
	cam := Camera{Host: host, Port: port, ProfileToken: profile}
	if err = client.PTZ(context.Background(), cam, Credentials{Username: "camera", Password: "secret"}, PTZCommand{Action: "move", Pan: 2, Tilt: -2}); err != nil {
		t.Fatal(err)
	}
	request, _ := ptzBody.Load().(string)
	if !strings.Contains(request, `x="1.000" y="-1.000"`) {
		t.Fatalf("PTZ velocity was not clamped: %s", request)
	}
	if err = client.PTZ(context.Background(), cam, Credentials{Username: "camera", Password: "secret"}, PTZCommand{Action: "relative", Pan: .2, Zoom: .3}); err != nil {
		t.Fatal(err)
	}
	request, _ = ptzBody.Load().(string)
	if !strings.Contains(request, "RelativeMove") || !strings.Contains(request, `x="0.300"`) {
		t.Fatalf("relative PTZ move was not sent: %s", request)
	}
	if err = client.PTZ(context.Background(), cam, Credentials{Username: "camera", Password: "secret"}, PTZCommand{Action: "move", Zoom: -.4}); err != nil {
		t.Fatal(err)
	}
	request, _ = ptzBody.Load().(string)
	if strings.Contains(request, "PanTilt") || !strings.Contains(request, `x="-0.400"`) {
		t.Fatalf("zoom-only PTZ move sent an unsupported pan/tilt axis: %s", request)
	}
}

func TestPullPointMotionEvent(t *testing.T) {
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		switch {
		case strings.Contains(string(body), "CreatePullPointSubscription"):
			fmt.Fprintf(w, `<s:Envelope><s:Body><CreatePullPointSubscriptionResponse><SubscriptionReference><Address>%s/pull</Address></SubscriptionReference></CreatePullPointSubscriptionResponse></s:Body></s:Envelope>`, server.URL)
		case strings.Contains(string(body), "PullMessages"):
			fmt.Fprint(w, `<s:Envelope><s:Body><PullMessagesResponse><NotificationMessage><Topic>tns1:RuleEngine/CellMotionDetector/Motion</Topic><Message><tt:SimpleItem Name="IsMotion" Value="true"/></Message></NotificationMessage></PullMessagesResponse></s:Body></s:Envelope>`)
		default:
			http.Error(w, "unexpected", http.StatusBadRequest)
		}
	}))
	defer server.Close()
	host, port := serverAddress(t, server.URL)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	received := make(chan string, 1)
	client := NewONVIFClient()
	go client.MonitorEvents(ctx, Camera{Host: host, Port: port}, Credentials{Username: "u", Password: "p"}, func(kind string, _ float64) {
		select {
		case received <- kind:
		default:
		}
		cancel()
	})
	select {
	case kind := <-received:
		if kind != "motion" {
			t.Fatalf("unexpected event %q", kind)
		}
	case <-ctx.Done():
		t.Fatal("motion event was not delivered")
	}
}

func serverAddress(t *testing.T, raw string) (string, int) {
	t.Helper()
	parsed, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	host, portText, err := net.SplitHostPort(parsed.Host)
	if err != nil {
		t.Fatal(err)
	}
	var port int
	if _, err = fmt.Sscanf(portText, "%d", &port); err != nil {
		t.Fatal(err)
	}
	return host, port
}
