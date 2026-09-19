import { key } from "./api";

export function live(
  cameraId: string,
  video: HTMLVideoElement,
  onStatus: (value: string) => void,
): () => void {
  if (!window.isSecureContext || typeof RTCPeerConnection === "undefined") {
    onStatus("Abra o painel por HTTPS em um navegador com suporte a WebRTC.");
    return () => {};
  }
  let closed = false;
  let stopAttempt: () => void = () => {};
  const endpoint = `/api/v1/cameras/${key(cameraId)}/live/webrtc/whep`;
  video.muted = true;
  video.playsInline = true;

  const connect = (browser: boolean) => {
    stopAttempt();
    if (closed) return;
    const controller = new AbortController();
    const pc = new RTCPeerConnection({
      bundlePolicy: "max-bundle",
      iceServers: [{ urls: "stun:stun.l.google.com:19302" }],
    });
    const stream = new MediaStream();
    let disposed = false;
    let session = "";
    let gatheringTimer: ReturnType<typeof setTimeout> | undefined;
    let requestTimer: ReturnType<typeof setTimeout> | undefined;
    let connectionTimer: ReturnType<typeof setTimeout> | undefined;
    let cancelGathering: (() => void) | undefined;
    const removeSession = () => {
      if (!session) return;
      void fetch(session, {
        method: "DELETE", headers: { "X-Valkyris-Viewer": "1" }, keepalive: true,
      }).catch(() => {});
      session = "";
    };
    const onPlaying = () => {
      // Audio can fire `playing` before any video has been decoded.
      if (disposed || video.videoWidth === 0) return;
      clearTimeout(connectionTimer);
      onStatus(browser ? "Ao vivo · vídeo compatível · áudio inicialmente silenciado" : "Ao vivo · áudio inicialmente silenciado");
    };
    const dispose = () => {
      if (disposed) return;
      disposed = true;
      controller.abort();
      clearTimeout(gatheringTimer);
      clearTimeout(requestTimer);
      clearTimeout(connectionTimer);
      cancelGathering?.();
      video.removeEventListener("playing", onPlaying);
      video.removeEventListener("resize", onPlaying);
      pc.close();
      stream.getTracks().forEach((track) => track.stop());
      video.srcObject = null;
      removeSession();
    };
    stopAttempt = dispose;
    const fail = (message: string, tryCompatible = false) => {
      if (disposed || closed) return;
      if (tryCompatible && !browser) {
        connect(true);
        return;
      }
      dispose();
      onStatus(message);
    };
    video.addEventListener("playing", onPlaying);
    video.addEventListener("resize", onPlaying);
    pc.addTransceiver("video", { direction: "recvonly" });
    pc.addTransceiver("audio", { direction: "recvonly" });
    pc.ontrack = (event) => {
      if (disposed) return;
      stream.addTrack(event.track);
      video.srcObject = stream;
      void video.play().catch(() => {
        if (!disposed) onStatus("Toque no botão de reprodução do vídeo para iniciar.");
      });
    };
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === "failed") {
        fail("Não foi possível conectar o vídeo. Verifique o acesso à porta de mídia 8189 pela rede ou VPN e tente reconectar.");
      }
    };
    void (async () => {
      try {
        onStatus(browser ? "Preparando vídeo compatível com este navegador…" : "Conectando ao vídeo ao vivo…");
        await pc.setLocalDescription(await pc.createOffer());
        await new Promise<void>((resolve) => {
          cancelGathering = resolve;
          if (pc.iceGatheringState === "complete" || disposed) { resolve(); return; }
          gatheringTimer = setTimeout(resolve, 8000);
          pc.onicegatheringstatechange = () => {
            if (pc.iceGatheringState === "complete") {
              clearTimeout(gatheringTimer);
              resolve();
            }
          };
        });
        if (disposed) return;
        const offer = pc.localDescription?.sdp;
        if (!offer) throw new Error("O navegador não conseguiu preparar a conexão de vídeo.");
        // Some browsers hide local candidates; remote ICE candidates can still
        // establish a connection. Do not reject a valid offer before signalling.
        requestTimer = setTimeout(() => controller.abort(), browser ? 30000 : 15000);
        const response = await fetch(endpoint + (browser ? "?profile=browser" : ""), {
          method: "POST",
          headers: { "X-Valkyris-Viewer": "1", "Content-Type": "application/sdp" },
          body: offer, signal: controller.signal,
        });
        clearTimeout(requestTimer);
        if (!response.ok) {
          if (response.status === 400 || response.status === 406) {
            fail("A câmera não ofereceu um formato de vídeo compatível.", true);
            return;
          }
          throw new Error(response.status === 401 ? "A sessão expirou. Entre novamente no painel." :
            response.status === 429 ? "Muitas tentativas de vídeo. Aguarde um minuto e reconecte." :
            `O servidor não conseguiu abrir o vídeo (HTTP ${response.status}).`);
        }
        const location = response.headers.get("Location");
        if (location) {
          const url = new URL(location, window.location.origin);
          if (url.origin !== window.location.origin || !url.pathname.startsWith(`${endpoint}/`)) {
            throw new Error("Sessão de vídeo inválida.");
          }
          session = url.href;
        }
        if (disposed) { removeSession(); return; }
        const answer = await response.text();
        if (disposed) return;
        try {
          await pc.setRemoteDescription({ type: "answer", sdp: answer });
        } catch {
          fail("O navegador não conseguiu negociar o formato de vídeo.", true);
          return;
        }
        if (disposed) return;
        connectionTimer = setTimeout(() => {
          const connected = pc.connectionState === "connected";
          fail(connected ? "A conexão abriu, mas não foi possível reproduzir o vídeo da câmera." :
            "O vídeo não conectou. Verifique se esta rede ou VPN alcança a porta de mídia 8189 do servidor.", connected);
        }, 20000);
        onPlaying();
      } catch (error) {
        fail(controller.signal.aborted ? "O servidor demorou para abrir o vídeo. Tente reconectar." :
          error instanceof Error ? error.message : "Vídeo indisponível.");
      }
    })();
  };
  connect(false);
  return () => { closed = true; stopAttempt(); };
}
