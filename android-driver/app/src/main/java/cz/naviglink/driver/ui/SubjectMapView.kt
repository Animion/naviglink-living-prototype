package cz.naviglink.driver.ui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import cz.naviglink.driver.BuildConfig
import cz.naviglink.driver.data.SignedSubject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Mapový widget pro Alert pane: zobrazuje polygon subjektu (modře) + bod
 * driverovy aktuální polohy (zelený kruh), zoomnutý tak, aby vše viděl najednou.
 *
 * Mapový tile servuje MapTiler (free tier, ~100k requests/month).
 * Styly podle preference: streets (žluté/červené silnice) nebo basic.
 */
@Composable
fun SubjectMapView(
    subject: SignedSubject,
    driverLat: Double?,
    driverLon: Double?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // MapLibre vyžaduje jednorázovou inicializaci. Bezpečné volat opakovaně;
    // interní guard zajistí jen jeden setup.
    remember { MapLibre.getInstance(context); Unit }

    val polygonCoords = remember(subject.id) { extractPolygon(subject.payload) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val mv = MapView(ctx)
            mv.onCreate(null)
            val styleUrl = "https://api.maptiler.com/maps/streets/style.json?key=${BuildConfig.MAPTILER_KEY}"
            mv.getMapAsync { map ->
                map.setStyle(styleUrl) { style ->
                    setupSubjectLayer(style, polygonCoords)
                    if (driverLat != null && driverLon != null) {
                        setupDriverLayer(style, driverLat, driverLon)
                    }
                    fitCamera(map, polygonCoords, driverLat, driverLon)
                }
                map.uiSettings.apply {
                    isLogoEnabled = false                  // MapLibre logo (free OK, ale skryjeme)
                    isAttributionEnabled = true            // OSM attribution povinný
                    isCompassEnabled = false
                    isZoomGesturesEnabled = true
                    isScrollGesturesEnabled = true
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                }
            }
            mv
        },
    )

    // Lifecycle pumping pro MapView — bez něj při rotaci/recompose mapview leakne.
    DisposableEffect(Unit) {
        onDispose {
            // MapView interně drží reference; v AndroidView factory se však uvolní
            // s odstraněním viewmodelu/Composeu. Pro bezpečnost nechceme volat
            // onDestroy zde, protože factory už View vrátil. WorkManager polling
            // pokračuje nezávisle.
        }
    }
}

private const val SUBJECT_FILL_SOURCE = "subject_fill_source"
private const val SUBJECT_FILL_LAYER = "subject_fill_layer"
private const val SUBJECT_LINE_LAYER = "subject_line_layer"
private const val DRIVER_SOURCE = "driver_source"
private const val DRIVER_LAYER = "driver_layer"

private fun setupSubjectLayer(style: Style, coords: List<LatLng>) {
    if (coords.isEmpty()) return
    val points = coords.map { Point.fromLngLat(it.longitude, it.latitude) }
    val polygon = Polygon.fromLngLats(listOf(points))
    val src = GeoJsonSource(SUBJECT_FILL_SOURCE, Feature.fromGeometry(polygon))
    style.addSource(src)

    style.addLayer(
        FillLayer(SUBJECT_FILL_LAYER, SUBJECT_FILL_SOURCE).withProperties(
            PropertyFactory.fillColor(Color.parseColor("#1e6091")),
            PropertyFactory.fillOpacity(0.25f),
        )
    )
    style.addLayer(
        LineLayer(SUBJECT_LINE_LAYER, SUBJECT_FILL_SOURCE).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#1e6091")),
            PropertyFactory.lineWidth(3f),
        )
    )
}

private fun setupDriverLayer(style: Style, lat: Double, lon: Double) {
    val point = Point.fromLngLat(lon, lat)
    val src = GeoJsonSource(DRIVER_SOURCE, Feature.fromGeometry(point))
    style.addSource(src)

    // Vlastní kruh přes CircleLayer není dostupný bez další importy — použijeme
    // jednoduchý fill kruh přes GeoJSON, nebo MapLibre CircleLayer.
    style.addLayer(
        org.maplibre.android.style.layers.CircleLayer(DRIVER_LAYER, DRIVER_SOURCE).withProperties(
            PropertyFactory.circleColor(Color.parseColor("#2d8050")),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f),
        )
    )
}

private fun fitCamera(
    map: org.maplibre.android.maps.MapLibreMap,
    polygon: List<LatLng>,
    driverLat: Double?,
    driverLon: Double?,
) {
    val all = polygon.toMutableList()
    if (driverLat != null && driverLon != null) {
        all.add(LatLng(driverLat, driverLon))
    }
    if (all.isEmpty()) return

    if (all.size == 1) {
        map.cameraPosition = CameraPosition.Builder()
            .target(all[0])
            .zoom(16.0)
            .build()
        return
    }

    val builder = LatLngBounds.Builder()
    for (p in all) builder.include(p)
    val bounds = builder.build()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, /* padding */ 60))
}

/**
 * Vytáhne souřadnice polygonu z `payload.geometry.coordinates[0]` (GeoJSON
 * Polygon format: outer ring first, holes after).
 *
 * GeoJSON coordinates jsou [lon, lat]; MapLibre `LatLng` má opačné pořadí.
 */
private fun extractPolygon(payload: JsonObject): List<LatLng> {
    val geom = payload["geometry"] as? JsonObject ?: return emptyList()
    val coordsRoot = geom["coordinates"] as? JsonArray ?: return emptyList()
    val outerRing = coordsRoot.firstOrNull() as? JsonArray ?: return emptyList()
    return outerRing.mapNotNull { item ->
        val pair = item as? JsonArray ?: return@mapNotNull null
        if (pair.size < 2) return@mapNotNull null
        val lon = (pair[0] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val lat = (pair[1] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        LatLng(lat, lon)
    }
}
