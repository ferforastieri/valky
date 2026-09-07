import L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { Person, Place, Location } from "./api";
import { date } from "./api";
export function familyMap(
  element: HTMLElement,
  people: Person[],
  places: Place[],
  history: Location[] = [],
): () => void {
  const map = L.map(element, { zoomAnimation: false }).setView(
    [-23.5505, -46.6333],
    12,
  );
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution:
      '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap</a>',
  }).addTo(map);
  const bounds: L.LatLngExpression[] = [];
  const text = (value: string) => {
    const span = document.createElement("span");
    span.textContent = value;
    return span;
  };
  places
    .filter((p) => p.enabled)
    .forEach((p) => {
      if (!Number.isFinite(p.latitude) || !Number.isFinite(p.longitude)) return;
      const circle = L.circle([p.latitude, p.longitude], {
        radius: p.radiusMeters,
        color: "#5c9853",
        fillOpacity: 0.1,
        weight: 2,
      })
        .addTo(map)
        .bindPopup(text(`${p.name} · ${p.radiusMeters} m`));
      bounds.push(
        circle.getBounds().getNorthWest(),
        circle.getBounds().getSouthEast(),
      );
    });
  people.forEach((p) => {
    if (p.lastLatitude == null || p.lastLongitude == null) return;
    const marker = text(p.name.slice(0, 1).toUpperCase());
    marker.className = "map-marker";
    marker.style.width = "32px";
    marker.style.height = "32px";
    L.marker([p.lastLatitude, p.lastLongitude], {
      icon: L.divIcon({ html: marker, className: "", iconSize: [32, 32] }),
    })
      .addTo(map)
      .bindPopup(text(`${p.name} · ${date(p.lastLocatedAt)}`));
    bounds.push([p.lastLatitude, p.lastLongitude]);
  });
  if (history.length) {
    const points = history.map(
      (p) => [p.latitude, p.longitude] as L.LatLngTuple,
    );
    L.polyline(points, { color: "#579c4e", weight: 3 }).addTo(map);
    history.forEach((p) =>
      L.circleMarker([p.latitude, p.longitude], { radius: 4, color: "#579c4e" })
        .addTo(map)
        .bindPopup(
          text(
            `${date(p.occurredAt)} · ${p.address || "Endereço indisponível"}`,
          ),
        ),
    );
    bounds.push(...points);
  }
  if (bounds.length)
    map.fitBounds(L.latLngBounds(bounds), {
      padding: [35, 35],
      maxZoom: 16,
      animate: false,
    });
  let disposed = false;
  const observer = new ResizeObserver(() => {
    if (!disposed) map.invalidateSize();
  });
  observer.observe(element);
  return () => {
    disposed = true;
    observer.disconnect();
    map.remove();
  };
}
