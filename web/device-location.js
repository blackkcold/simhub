/** Browser-only location presentation. No geolocation API, timers or hidden tile requests. */
export function validateNodeLocation(payload, event) {
  if (!payload || typeof payload !== 'object' || !event || event.kind !== 'device.location') return null;
  const lat=payload.latitude, lon=payload.longitude, accuracy=payload.accuracyMeters;
  const reported=payload.sampledAt;
  if (typeof lat!=='number' || typeof lon!=='number' || !Number.isFinite(lat) || !Number.isFinite(lon)
      || Math.abs(lat)>85 || Math.abs(lon)>180
      || typeof accuracy!=='number' || !Number.isFinite(accuracy) || accuracy<0 || accuracy>100000
      || !Number.isSafeInteger(reported) || reported<=0 || reported>Date.now()/1000+300) return null;
  return {latitude:lat,longitude:lon,accuracyMeters:accuracy,sampledAt:reported,
    receivedAt:Number.isSafeInteger(event.receivedAt)?event.receivedAt:null};
}
export function osmEmbedUrl(location) {
  if (!location || !Number.isFinite(location.latitude) || !Number.isFinite(location.longitude)
      || Math.abs(location.latitude)>85 || Math.abs(location.longitude)>180) return null;
  const lat=location.latitude, lon=location.longitude;
  const span=.008, west=Math.max(-180,lon-span), east=Math.min(180,lon+span);
  const south=Math.max(-85,lat-span), north=Math.min(85,lat+span);
  const bbox=[west,south,east,north].map(n=>n.toFixed(6)).join('%2C');
  return 'https://www.openstreetmap.org/export/embed.html?bbox='+bbox+
    '&layer=mapnik&marker='+lat.toFixed(6)+'%2C'+lon.toFixed(6);
}
export function osmExternalUrl(location) {
  if(!osmEmbedUrl(location))return null;
  return 'https://www.openstreetmap.org/?mlat='+location.latitude.toFixed(6)+
    '&mlon='+location.longitude.toFixed(6)+'#map=15/'+
    location.latitude.toFixed(6)+'/'+location.longitude.toFixed(6);
}
