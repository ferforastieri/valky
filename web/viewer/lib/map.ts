import L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { Person, Place, Location } from "./api";
import { date } from "./api";
import { historySegments, validHistoryPoint } from "./location-history";
export function familyMap(
  element: HTMLElement,
  people: Person[],
  places: Place[],
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
  const peopleBounds: L.LatLngExpression[] = [];
  people.forEach((p) => {
    if (p.lastLatitude == null || p.lastLongitude == null || !Number.isFinite(p.lastLatitude) || !Number.isFinite(p.lastLongitude)) return;
    const marker = text(p.name.slice(0, 1).toUpperCase());
    marker.className = "map-marker";
    marker.style.width = "32px";
    marker.style.height = "32px";
    L.marker([p.lastLatitude, p.lastLongitude], {
      icon: L.divIcon({ html: marker, className: "", iconSize: [32, 32] }),
    })
      .addTo(map)
      .bindPopup(text(`${p.name} · ${date(p.lastLocatedAt)}`));
    peopleBounds.push([p.lastLatitude, p.lastLongitude]);
  });
  // Family framing follows people, even when saved areas are far away.
  const visibleBounds = peopleBounds.length ? peopleBounds : bounds;
  if (visibleBounds.length)
    map.fitBounds(L.latLngBounds(visibleBounds), {
      padding: [35, 35],
      maxZoom: peopleBounds.length ? 18 : 16,
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

export function historyMap(
  element: HTMLElement,
  onSelect: (index: number) => void,
) {
  const map = L.map(element, { zoomAnimation: false }).setView([0, 0], 2);
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap</a>',
  }).addTo(map);
  const layer = L.layerGroup().addTo(map);
  let points: Location[] = [];
  let selected: number | null = null;
  let accuracy: L.Circle | undefined;
  const markers = new Map<number, L.CircleMarker>();
  const fit = () => {
    selected = null;
    accuracy?.remove();
    markers.forEach(marker => marker.setStyle({ color: "#ffffff", fillColor: "#1d4ed8", radius: 4 }));
    const coordinates = points.filter(validHistoryPoint).map(p => [p.latitude, p.longitude] as L.LatLngTuple);
    if (coordinates.length) map.fitBounds(L.latLngBounds(coordinates), { padding: [25, 25], maxZoom: 17, animate: false });
  };
  const select = (index: number) => {
    const point = points[index];
    if (!point || !validHistoryPoint(point)) return;
    selected = index;
    markers.forEach((marker, key) => marker.setStyle({ radius: key === index ? 6 : 4, fillColor: key === index ? "#9a3412" : "#1d4ed8" }));
    accuracy?.remove();
    accuracy = L.circle([point.latitude, point.longitude], { radius: Math.max(1, point.accuracy), color: "#9a3412", fillOpacity: .06, weight: 1, interactive: false }).addTo(layer);
    map.setView([point.latitude, point.longitude], 17, { animate: false });
    markers.get(index)?.openPopup();
  };
  const update = (next: Location[]) => {
    points = next;
    layer.clearLayers();
    markers.clear();
    historySegments(points).forEach(segment => {
      const coordinates = segment.map(p => [p.latitude, p.longitude] as L.LatLngTuple);
      L.polyline(coordinates, { color: "#ffffff", weight: 4, dashArray: "6 4", interactive: false }).addTo(layer);
      L.polyline(coordinates, { color: "#1d4ed8", weight: 2, dashArray: "6 4", interactive: false }).addTo(layer);
    });
    points.forEach((point, index) => {
      if (!validHistoryPoint(point)) return;
      const popup = document.createElement("span");
      popup.textContent = `${date(point.occurredAt)} · ${point.address || 'Localização registrada'} · precisão de ${Math.round(point.accuracy)} m`;
      const marker = L.circleMarker([point.latitude, point.longitude], { radius: 4, color: "#ffffff", fillColor: "#1d4ed8", fillOpacity: 1, weight: 1.5 })
        .addTo(layer).bindPopup(popup).on("click", () => onSelect(index));
      L.circleMarker([point.latitude, point.longitude], { radius: 16, stroke: false, fillOpacity: 0 })
        .addTo(layer).on("click", () => onSelect(index));
      markers.set(index, marker);
    });
    map.invalidateSize();
    if (selected != null) select(selected); else fit();
  };
  const observer = new ResizeObserver(() => map.invalidateSize());
  observer.observe(element);
  return { update, select, fit, destroy: () => { observer.disconnect(); map.remove(); } };
}
