import { key } from "./api";
export function live(
  cameraId: string,
  video: HTMLVideoElement,
  onStatus: (value: string) => void,
): () => void {
  const controller = new AbortController();
  const pc = new RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }],
  });
  let session = "";
  let closed = false;
  let timer: ReturnType<typeof setTimeout>;
  const removeSession = () => {
    if (session) {
      void fetch(session, {
        method: "DELETE",
        headers: { "X-Valkyris-Viewer": "1" },
        keepalive: true,
      }).catch(() => {});
      session = "";
    }
  };
  pc.addTransceiver("video", { direction: "recvonly" });
  pc.addTransceiver("audio", { direction: "recvonly" });
  const stream = new MediaStream();
  pc.ontrack = (event) => {
    stream.addTrack(event.track);
    video.srcObject = stream;
    void video.play().catch(() => {});
  };
  const onPlaying = () => {
    if (closed) return;
    onStatus("Ao vivo · áudio inicialmente silenciado");
    clearTimeout(timer);
  };
  video.addEventListener("playing", onPlaying);
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === "failed")
      onStatus(
        "Não foi possível conectar o vídeo. Verifique a rede e tente reconectar.",
      );
  };
  timer = setTimeout(
    () =>
      onStatus(
        "O vídeo ainda não conectou. Verifique se a rede permite alcançar o servidor de mídia.",
      ),
    18000,
  );
  void (async () => {
    try {
      onStatus("Conectando ao vídeo ao vivo…");
      await pc.setLocalDescription(await pc.createOffer());
      await new Promise<void>((resolve) => {
        if (pc.iceGatheringState === "complete") {
          resolve();
          return;
        }
        const t = setTimeout(resolve, 8000);
        pc.onicegatheringstatechange = () => {
          if (pc.iceGatheringState === "complete") {
            clearTimeout(t);
            resolve();
          }
        };
      });
      if (closed) return;
      const offer = pc.localDescription?.sdp;
      if (!offer?.split(/\r?\n/).some((line) => line.startsWith("a=candidate:")))
        throw new Error("Não foi possível obter um endereço de rede para conectar a câmera.");
      const response = await fetch(
        `/api/v1/cameras/${key(cameraId)}/live/webrtc/whep`,
        {
          method: "POST",
          headers: {
            "X-Valkyris-Viewer": "1",
            "Content-Type": "application/sdp",
          },
          body: offer,
          signal: AbortSignal.any([
            controller.signal,
            AbortSignal.timeout(15000),
          ]),
        },
      );
      if (!response.ok)
        throw new Error("O servidor não conseguiu abrir o vídeo.");
      const location = response.headers.get("Location");
      if (location) {
        const url = new URL(location, window.location.origin);
        if (
          url.origin !== window.location.origin ||
          !url.pathname.startsWith(
            `/api/v1/cameras/${key(cameraId)}/live/webrtc/whep/`,
          )
        )
          throw new Error("Sessão de vídeo inválida.");
        session = url.href;
      }
      if (closed) {
        removeSession();
        return;
      }
      await pc.setRemoteDescription({
        type: "answer",
        sdp: await response.text(),
      });
    } catch (error) {
      if (!closed)
        onStatus(
          error instanceof Error ? error.message : "Vídeo indisponível.",
        );
    }
  })();
  return () => {
    closed = true;
    controller.abort();
    clearTimeout(timer);
    video.removeEventListener("playing", onPlaying);
    pc.close();
    video.srcObject = null;
    removeSession();
  };
}
