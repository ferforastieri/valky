import type { Location } from "./api";

export function validHistoryPoint(point: Location): boolean {
  return Number.isFinite(point.latitude) && Number.isFinite(point.longitude) &&
    Math.abs(point.latitude) <= 90 && Math.abs(point.longitude) <= 180;
}

export function historySegments(points: Location[]): Location[][] {
  const sorted = points.filter(validHistoryPoint).sort((a, b) => Date.parse(a.occurredAt) - Date.parse(b.occurredAt));
  const segments: Location[][] = [];
  for (const point of sorted) {
    const previous = segments.at(-1)?.at(-1);
    const gap = previous ? Date.parse(point.occurredAt) - Date.parse(previous.lastSeenAt || previous.occurredAt) : Infinity;
    if (!Number.isFinite(gap) || gap < 0 || gap > 30 * 60000) segments.push([]);
    segments.at(-1)!.push(point);
  }
  return segments.filter(segment => segment.length > 1);
}
