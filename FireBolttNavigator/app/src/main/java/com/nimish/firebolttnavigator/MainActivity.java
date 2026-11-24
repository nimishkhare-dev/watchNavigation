package com.nimish.firebolttnavigator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements LocationListener, WatchBleClient.Listener {
    private static final int REQUEST_LOCATION = 101;
    private static final int REQUEST_BLUETOOTH = 102;
    private static final long WATCH_FACE_MIN_REFRESH_INTERVAL_MS = 45_000L;
    private static final float WATCH_FACE_MIN_MOVEMENT_METERS = 35f;
    private static final long WATCH_ALERT_MIN_INTERVAL_MS = 12_000L;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText destinationInput;
    private Spinner profileSpinner;
    private TextView statusText;
    private TextView routeSummaryText;
    private TextView nextTurnText;
    private TextView watchText;
    private TextView progressText;
    private ProgressBar progressBar;
    private WebView mapView;
    private Button routeButton;
    private Button sendWatchButton;

    private LocationManager locationManager;
    private Location currentLocation;
    private boolean locationUpdatesOn;
    private boolean pendingRoute;
    private boolean navigating;
    private boolean mapReady;
    private boolean watchUploadInProgress;
    private boolean watchMirrorEnabled;
    private boolean watchRefreshQueued;
    private boolean watchFirstFaceInstalled;
    private long lastWatchFaceUploadStartedAt;
    private Location lastWatchFaceLocation;
    private String lastWatchFaceTurn = "";
    private String lastWatchAlertKey = "";
    private long lastWatchAlertAt;
    private Runnable watchRefreshRunnable;
    private RouteEngine.Place selectedDestination;
    private RouteEngine.Route currentRoute;
    private int nextStepIndex;
    private String lastSentTurn = "";
    private WatchBleClient watchClient;
    private List<RouteEngine.Place> searchResults = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 26, 42));
        getWindow().setNavigationBarColor(Color.rgb(16, 26, 42));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        watchClient = new WatchBleClient(this, this);
        setContentView(createUi());
    }

    private View createUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(243, 246, 250));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(22));
        scroll.addView(root);

        TextView title = text("FireBoltt Navigator", 25, Color.rgb(16, 26, 42), true);
        root.addView(title);
        TextView subtitle = text("Live turn-by-turn navigation on your phone, with a periodically refreshed route view on your Fire-Boltt 046.", 13, Color.rgb(87, 101, 122), false);
        LinearLayout.LayoutParams subParams = params(-1, -2); subParams.topMargin = dp(5);
        root.addView(subtitle, subParams);

        LinearLayout destinationCard = card(root);
        destinationInput = new EditText(this);
        destinationInput.setSingleLine(true);
        destinationInput.setHint("Search destination (e.g. Phoenix Marketcity Pune)");
        destinationInput.setTextColor(Color.rgb(22, 34, 52));
        destinationInput.setHintTextColor(Color.rgb(130, 144, 164));
        destinationInput.setTextSize(15);
        destinationCard.addView(destinationInput, params(-1, dp(52)));

        Button searchButton = button("SEARCH DESTINATION");
        destinationCard.addView(searchButton, params(-1, dp(46)));
        searchButton.setOnClickListener(v -> searchDestination());

        TextView selected = text("No destination selected", 12, Color.rgb(87, 101, 122), false);
        destinationCard.addView(selected, params(-1, -2));
        // Keep the selected-destination label in the summary field until a route is calculated.
        selectedDestinationLabel = selected;

        LinearLayout profileRow = new LinearLayout(this);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        profileRow.setGravity(Gravity.CENTER_VERTICAL);
        profileSpinner = new Spinner(this);
        String[] modes = {"Driving"};
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes);
        profileSpinner.setAdapter(modeAdapter);
        profileRow.addView(profileSpinner, new LinearLayout.LayoutParams(0, dp(46), 1f));
        routeButton = button("CALCULATE ROUTE");
        LinearLayout.LayoutParams routeButtonParams = new LinearLayout.LayoutParams(0, dp(46), 1.3f);
        routeButtonParams.leftMargin = dp(8);
        profileRow.addView(routeButton, routeButtonParams);
        destinationCard.addView(profileRow, params(-1, -2));
        routeButton.setOnClickListener(v -> beginRoute());

        routeSummaryText = text("Route: —", 13, Color.rgb(22, 34, 52), true);
        LinearLayout.LayoutParams routeSummaryParams = params(-1, -2); routeSummaryParams.topMargin = dp(8);
        destinationCard.addView(routeSummaryText, routeSummaryParams);

        mapView = new WebView(this);
        mapView.setBackgroundColor(Color.rgb(226, 232, 240));
        WebSettings webSettings = mapView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setUserAgentString("FireBolttNavigator/0.1 Android WebView");
        mapView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                mapReady = true;
                if (currentRoute != null) drawPhoneRoute();
                if (currentLocation != null) drawCurrentLocation();
            }
        });
        mapView.loadUrl("file:///android_asset/map.html");
        LinearLayout.LayoutParams mapParams = params(-1, dp(400)); mapParams.topMargin = dp(12);
        root.addView(mapView, mapParams);

        LinearLayout navigationCard = card(root);
        TextView navigationTitle = text("LIVE NAVIGATION", 14, Color.rgb(16, 26, 42), true);
        navigationCard.addView(navigationTitle, params(-1, -2));
        nextTurnText = text("Choose a destination and calculate a route.", 16, Color.rgb(22, 34, 52), true);
        LinearLayout.LayoutParams nextParams = params(-1, -2); nextParams.topMargin = dp(8);
        navigationCard.addView(nextTurnText, nextParams);
        statusText = text("Waiting for GPS permission.", 12, Color.rgb(87, 101, 122), false);
        LinearLayout.LayoutParams statusParams = params(-1, -2); statusParams.topMargin = dp(8);
        navigationCard.addView(statusText, statusParams);
        Button stopButton = button("STOP NAVIGATION");
        LinearLayout.LayoutParams stopParams = params(-1, dp(44)); stopParams.topMargin = dp(10);
        navigationCard.addView(stopButton, stopParams);
        stopButton.setOnClickListener(v -> stopNavigation());

        LinearLayout watchCard = card(root);
        TextView watchHeading = text("FIRE-BOLTT 046", 14, Color.rgb(16, 26, 42), true);
        watchCard.addView(watchHeading, params(-1, -2));
        watchText = text("Watch not connected", 12, Color.rgb(87, 101, 122), false);
        LinearLayout.LayoutParams watchTextParams = params(-1, -2); watchTextParams.topMargin = dp(5);
        watchCard.addView(watchText, watchTextParams);
        Button connectButton = button("CONNECT WATCH");
        LinearLayout.LayoutParams connectParams = params(-1, dp(44)); connectParams.topMargin = dp(10);
        watchCard.addView(connectButton, connectParams);
        connectButton.setOnClickListener(v -> requestBluetoothAndConnect());

        sendWatchButton = button("START LIVE NAVIGATION ON WATCH");
        LinearLayout.LayoutParams sendParams = params(-1, dp(48)); sendParams.topMargin = dp(8);
        watchCard.addView(sendWatchButton, sendParams);
        sendWatchButton.setOnClickListener(v -> sendRouteToWatch());
        sendWatchButton.setEnabled(false);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pbParams = params(-1, dp(8)); pbParams.topMargin = dp(12);
        watchCard.addView(progressBar, pbParams);
        progressText = text("Watch upload has not started.", 11, Color.rgb(87, 101, 122), false);
        LinearLayout.LayoutParams progressParams = params(-1, -2); progressParams.topMargin = dp(5);
        watchCard.addView(progressText, progressParams);

        TextView disclaimer = text("Watch mirror uses experimental MoYoung Type-C 0x74 face uploads. After you start it once, the watch view refreshes when you move about 35 m or reach a new turn, with at least 45 seconds between full uploads. Turn-distance notifications are sent separately. This exact firmware still needs on-watch testing; keep this app open while navigating.",
                11, Color.rgb(120, 91, 34), false);
        LinearLayout.LayoutParams disclaimerParams = params(-1, -2); disclaimerParams.topMargin = dp(12);
        root.addView(disclaimer, disclaimerParams);

        TextView attribution = text("Map data © OpenStreetMap contributors. Routing/geocoding are public best-effort services.",
                10, Color.rgb(100, 116, 139), false);
        LinearLayout.LayoutParams attributionParams = params(-1, -2); attributionParams.topMargin = dp(8);
        root.addView(attribution, attributionParams);
        return scroll;
    }

    private TextView selectedDestinationLabel;

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        card.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams lp = params(-1, -2); lp.topMargin = dp(12);
        parent.addView(card, lp);
        return card;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        return text;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(13, 148, 136)));
        return button;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void searchDestination() {
        String query = destinationInput.getText().toString().trim();
        if (query.length() < 3) { toast("Enter at least three characters."); return; }
        status("Searching destination …");
        io.execute(() -> {
            try {
                List<RouteEngine.Place> places = RouteEngine.searchPlaces(query);
                runOnUiThread(() -> {
                    if (places.isEmpty()) { status("No matching places found. Try a more specific name."); return; }
                    searchResults = places;
                    String[] labels = new String[places.size()];
                    for (int i = 0; i < places.size(); i++) labels[i] = places.get(i).name;
                    new AlertDialog.Builder(this).setTitle("Choose destination")
                            .setItems(labels, (dialog, which) -> {
                                selectedDestination = places.get(which);
                                selectedDestinationLabel.setText("Destination: " + selectedDestination.name);
                                status("Destination selected. Get a GPS fix, then calculate the route.");
                            }).setNegativeButton("Cancel", null).show();
                });
            } catch (Exception e) { runOnUiThread(() -> status("Destination search failed: " + safeMessage(e))); }
        });
    }

    private void beginRoute() {
        if (selectedDestination == null) { toast("Search and select a destination first."); return; }
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pendingRoute = true;
        status("Getting your current location …");
        startLocationUpdates();
        if (currentLocation != null && pendingRoute) calculateRouteFromCurrentLocation();
    }

    private void calculateRouteFromCurrentLocation() {
        if (selectedDestination == null || currentLocation == null) return;
        pendingRoute = false;
        Location origin = new Location(currentLocation);
        String profile = selectedProfile();
        status("Calculating " + profile + " route …");
        io.execute(() -> {
            try {
                RouteEngine.Route route = RouteEngine.getRoute(origin, selectedDestination, profile);
                runOnUiThread(() -> {
                    currentRoute = route;
                    navigating = true;
                    nextStepIndex = firstTurnStep(route);
                    lastSentTurn = "";
                    lastWatchAlertKey = "";
                    lastWatchAlertAt = 0L;
                    routeSummaryText.setText("Route: " + route.distanceLabel() + "  •  " + route.durationLabel());
                    status("Route ready. Keep this screen open for live location updates.");
                    drawPhoneRoute();
                    refreshTurnInstruction();
                    sendWatchButton.setEnabled(watchClient.isConnected());
                    if (watchClient.isConnected()) sendCurrentTurnAlert();
                });
            } catch (Exception e) { runOnUiThread(() -> status("Route calculation failed: " + safeMessage(e))); }
        });
    }

    private String selectedProfile() { return "driving"; }

    private int firstTurnStep(RouteEngine.Route route) {
        if (route.steps == null || route.steps.isEmpty()) return 0;
        for (int i = 0; i < route.steps.size(); i++) {
            if (!"depart".equals(route.steps.get(i).maneuverType)) return i;
        }
        return 0;
    }

    private void refreshTurnInstruction() {
        if (currentRoute == null || currentRoute.steps.isEmpty()) {
            nextTurnText.setText("Follow the highlighted route."); return;
        }
        int index = Math.min(nextStepIndex, currentRoute.steps.size() - 1);
        RouteEngine.Step step = currentRoute.steps.get(index);
        float[] results = new float[1];
        Location.distanceBetween(currentLocation == null ? currentRoute.origin.latitude : currentLocation.getLatitude(),
                currentLocation == null ? currentRoute.origin.longitude : currentLocation.getLongitude(),
                step.latitude, step.longitude, results);
        String distance = formatDistance(results[0]);
        nextTurnText.setText(step.instruction + "\nIn " + distance);
        if (watchText != null && watchClient.isConnected()) watchText.setText("Connected • " + distance + " to next maneuver");
    }

    private void updateNavigationProgress(Location location) {
        if (!navigating || currentRoute == null || currentRoute.steps.isEmpty()) return;
        int last = currentRoute.steps.size() - 1;
        while (nextStepIndex < last) {
            RouteEngine.Step step = currentRoute.steps.get(nextStepIndex);
            float[] distance = new float[1];
            Location.distanceBetween(location.getLatitude(), location.getLongitude(), step.latitude, step.longitude, distance);
            if (distance[0] < 35) nextStepIndex++;
            else break;
        }
        refreshTurnInstruction();
        sendCurrentTurnAlert();
    }

    private void sendCurrentTurnAlert() {
        if (!watchClient.isConnected() || currentRoute == null || currentRoute.steps.isEmpty()) return;
        RouteEngine.Step step = currentRoute.steps.get(Math.min(nextStepIndex, currentRoute.steps.size() - 1));
        float meters = Float.MAX_VALUE;
        if (currentLocation != null) {
            float[] d = new float[1];
            Location.distanceBetween(currentLocation.getLatitude(), currentLocation.getLongitude(), step.latitude, step.longitude, d);
            meters = d[0];
        }
        String alert = step.instruction + (meters == Float.MAX_VALUE ? "" : " in " + formatDistance(meters));
        int distanceBucket = meters == Float.MAX_VALUE ? -1 : Math.round(meters / 50f);
        String alertKey = step.instruction + "|" + distanceBucket;
        boolean turnChanged = !step.instruction.equals(lastSentTurn);
        boolean distanceChanged = !alertKey.equals(lastWatchAlertKey);
        long now = System.currentTimeMillis();
        if (turnChanged || (distanceChanged && now - lastWatchAlertAt >= WATCH_ALERT_MIN_INTERVAL_MS)) {
            watchClient.sendTurnAlert(alert);
            lastSentTurn = step.instruction;
            lastWatchAlertKey = alertKey;
            lastWatchAlertAt = now;
        }
    }

    private void drawPhoneRoute() {
        if (!mapReady || currentRoute == null) return;
        JSONArray points = new JSONArray();
        try {
            for (RouteEngine.Point p : currentRoute.geometry) {
                JSONArray pair = new JSONArray(); pair.put(p.latitude); pair.put(p.longitude); points.put(pair);
            }
            String start = "[" + currentRoute.origin.latitude + "," + currentRoute.origin.longitude + "]";
            String destination = "[" + currentRoute.destination.latitude + "," + currentRoute.destination.longitude + "]";
            mapView.evaluateJavascript("setRoute(" + points + "," + start + "," + destination + ")", null);
            if (currentLocation != null) drawCurrentLocation();
        } catch (Exception e) { status("Could not draw route: " + safeMessage(e)); }
    }

    private void drawCurrentLocation() {
        if (mapReady && currentLocation != null) {
            float bearing = currentLocation.hasBearing() ? currentLocation.getBearing() : 0f;
            float accuracy = currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 0f;
            mapView.evaluateJavascript(String.format(Locale.US, "setCurrent(%.7f,%.7f,%.1f,%.1f)", currentLocation.getLatitude(), currentLocation.getLongitude(), bearing, accuracy), null);
        }
    }

    private void startLocationUpdates() {
        if (locationUpdatesOn || !hasLocationPermission()) return;
        try {
            boolean hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            String provider;
            if (hasFine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            } else {
                provider = LocationManager.GPS_PROVIDER;
            }
            locationManager.requestLocationUpdates(provider, 1500L, 3f, this, Looper.getMainLooper());
            locationUpdatesOn = true;
            Location last = locationManager.getLastKnownLocation(provider);
            if (last != null) {
                currentLocation = last;
                drawCurrentLocation();
                if (pendingRoute) calculateRouteFromCurrentLocation();
            }
        } catch (SecurityException e) {
            status("Location permission was not granted.");
        } catch (Exception e) {
            status("Could not start GPS: " + safeMessage(e));
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationUpdatesOn) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) {}
        }
        locationUpdatesOn = false;
    }

    @Override public void onLocationChanged(Location location) {
        currentLocation = location;
        drawCurrentLocation();
        if (pendingRoute && selectedDestination != null) calculateRouteFromCurrentLocation();
        updateNavigationProgress(location);
        maybeRefreshWatchFace(location);
    }

    private void stopNavigation() {
        navigating = false; pendingRoute = false; currentRoute = null;
        watchMirrorEnabled = false;
        watchRefreshQueued = false;
        if (watchRefreshRunnable != null) main.removeCallbacks(watchRefreshRunnable);
        stopLocationUpdates();
        routeSummaryText.setText("Route: —");
        nextTurnText.setText("Navigation stopped.");
        status("Navigation stopped.");
        sendWatchButton.setEnabled(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void sendRouteToWatch() {
        if (currentRoute == null) { toast("Calculate a route first."); return; }
        if (!watchClient.isConnected()) { toast("Connect the Fire-Boltt first."); return; }
        if (watchUploadInProgress) return;

        // One explicit user action installs the route view and enables subsequent live refreshes.
        watchMirrorEnabled = true;
        watchFirstFaceInstalled = false;
        lastWatchFaceLocation = null;
        lastWatchFaceTurn = "";
        watchRefreshQueued = false;
        startWatchFaceUpload(false);
    }

    private void startWatchFaceUpload(boolean liveRefresh) {
        if (currentRoute == null || !watchClient.isConnected()) return;
        if (watchUploadInProgress) {
            watchRefreshQueued = true;
            return;
        }

        watchUploadInProgress = true;
        watchRefreshQueued = false;
        lastWatchFaceUploadStartedAt = System.currentTimeMillis();
        Location locationSnapshot = currentLocation == null ? null : new Location(currentLocation);
        if (locationSnapshot != null) lastWatchFaceLocation = locationSnapshot;
        String instruction = currentTurnInstruction();
        lastWatchFaceTurn = instruction;
        String distance = nextTurnDistanceLabel();
        double lat = locationSnapshot == null ? currentRoute.origin.latitude : locationSnapshot.getLatitude();
        double lon = locationSnapshot == null ? currentRoute.origin.longitude : locationSnapshot.getLongitude();
        RouteEngine.Route routeSnapshot = currentRoute;
        progressBar.setProgress(0);
        progressText.setText(liveRefresh ? "Preparing live watch update …" : "Preparing 368 × 448 navigation view …");

        io.execute(() -> {
            try {
                WatchFaceEncoder.BuildResult result = WatchFaceEncoder.build(routeSnapshot, lat, lon, instruction, distance);
                runOnUiThread(() -> {
                    if (!watchClient.isConnected()) {
                        watchUploadInProgress = false;
                        progressText.setText("Watch disconnected before the navigation view could be sent.");
                        return;
                    }
                    progressText.setText((liveRefresh ? "Refreshing" : "Installing") + " watch view (" + result.faceFile.length
                            + " bytes). Background RLE=" + result.backgroundRle + ", preview RLE=" + result.previewRle + ".");
                    watchClient.uploadTypeCFace(result.faceFile);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    watchUploadInProgress = false;
                    watchMirrorEnabled = false;
                    watchRefreshQueued = false;
                    progressText.setText("Watch face build failed: " + safeMessage(e));
                });
            }
        });
    }

    private void maybeRefreshWatchFace(Location location) {
        if (!watchMirrorEnabled || !navigating || currentRoute == null || location == null
                || !watchClient.isConnected()) return;

        String instruction = currentTurnInstruction();
        float movedMeters = Float.MAX_VALUE;
        if (lastWatchFaceLocation != null) movedMeters = lastWatchFaceLocation.distanceTo(location);
        boolean turnChanged = !instruction.equals(lastWatchFaceTurn);
        boolean movedEnough = movedMeters >= WATCH_FACE_MIN_MOVEMENT_METERS;
        if (!turnChanged && !movedEnough) return;

        if (watchUploadInProgress) {
            watchRefreshQueued = true;
            return;
        }

        long elapsed = System.currentTimeMillis() - lastWatchFaceUploadStartedAt;
        long delay = WATCH_FACE_MIN_REFRESH_INTERVAL_MS - elapsed;
        if (delay > 0) {
            watchRefreshQueued = true;
            if (watchRefreshRunnable != null) main.removeCallbacks(watchRefreshRunnable);
            watchRefreshRunnable = () -> {
                if (!watchRefreshQueued) return;
                watchRefreshQueued = false;
                maybeRefreshWatchFace(currentLocation);
            };
            main.postDelayed(watchRefreshRunnable, delay);
            return;
        }

        startWatchFaceUpload(true);
    }

    private String currentTurnInstruction() {
        if (currentRoute == null || currentRoute.steps == null || currentRoute.steps.isEmpty()) return "Follow the route";
        return currentRoute.steps.get(Math.min(nextStepIndex, currentRoute.steps.size() - 1)).instruction;
    }

    private String nextTurnDistanceLabel() {
        if (currentRoute == null || currentRoute.steps.isEmpty()) return "Follow the highlighted line";
        RouteEngine.Step step = currentRoute.steps.get(Math.min(nextStepIndex, currentRoute.steps.size() - 1));
        if (currentLocation == null) return "Next: " + step.instruction;
        float[] d = new float[1];
        Location.distanceBetween(currentLocation.getLatitude(), currentLocation.getLongitude(), step.latitude, step.longitude, d);
        return "Next turn in " + formatDistance(d[0]);
    }

    private void requestBluetoothAndConnect() {
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> missing = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), REQUEST_BLUETOOTH); return; }
        }
        watchClient.connect(WatchBleClient.DEFAULT_ADDRESS);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            if (hasLocationPermission()) beginRoute();
            else status("Location permission is required to calculate a route from your current position.");
        } else if (requestCode == REQUEST_BLUETOOTH) {
            boolean granted = true;
            for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) granted = false;
            if (granted) watchClient.connect(WatchBleClient.DEFAULT_ADDRESS);
            else status("Nearby devices permission is required to connect to the watch.");
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (navigating) startLocationUpdates();
    }

    @Override protected void onStop() {
        super.onStop();
        stopLocationUpdates();
    }

    @Override protected void onDestroy() {
        stopLocationUpdates();
        if (mapView != null) mapView.destroy();
        watchClient.disconnect();
        io.shutdownNow();
        super.onDestroy();
    }

    // WatchBleClient.Listener
    @Override public void onStatus(String message) { runOnUiThread(() -> status(message)); }

    @Override public void onConnected(String deviceName) {
        runOnUiThread(() -> {
            watchText.setText("Connected: " + deviceName + " (MoYoung V2 services ready)");
            sendWatchButton.setEnabled(currentRoute != null);
            toast("Watch connected.");
            if (navigating && currentRoute != null) sendCurrentTurnAlert();
            if (watchMirrorEnabled && navigating) {
                lastWatchFaceLocation = null;
                maybeRefreshWatchFace(currentLocation);
            }
        });
    }

    @Override public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            watchText.setText("Disconnected"); sendWatchButton.setEnabled(false);
            if (watchUploadInProgress) {
                watchUploadInProgress = false;
                watchMirrorEnabled = false;
                watchRefreshQueued = false;
                progressText.setText("Watch disconnected during face transfer. Reconnect and start watch navigation again.");
            }
            status(reason);
        });
    }

    @Override public void onUploadProgress(int sentBytes, int totalBytes, int chunkIndex, int totalChunks) {
        runOnUiThread(() -> {
            int percentage = totalBytes == 0 ? 0 : (int) ((sentBytes * 100L) / totalBytes);
            progressBar.setProgress(percentage);
            progressText.setText((watchFirstFaceInstalled ? "Updating live watch view: " : "Installing watch view: ")
                    + percentage + "% • chunk " + chunkIndex + "/" + totalChunks);
        });
    }

    @Override public void onUploadComplete(String ackHex) {
        runOnUiThread(() -> {
            watchUploadInProgress = false;
            progressBar.setProgress(100);
            progressText.setText("Watch returned completion value " + ackHex + ". Face activation command sent. Check the watch face selector.");
            watchText.setText(watchMirrorEnabled ? "Connected • live navigation mirror enabled" : "Connected • route view sent");
            if (!watchFirstFaceInstalled) toast("Watch navigation view installed.");
            watchFirstFaceInstalled = true;
            if (watchMirrorEnabled && navigating) {
                main.postDelayed(() -> maybeRefreshWatchFace(currentLocation), 500L);
            }
        });
    }

    @Override public void onError(String message) {
        runOnUiThread(() -> {
            if (watchUploadInProgress) {
                watchUploadInProgress = false;
                watchMirrorEnabled = false;
                watchRefreshQueued = false;
                if (watchRefreshRunnable != null) main.removeCallbacks(watchRefreshRunnable);
                progressText.setText("Watch upload did not complete: " + message + " Start watch navigation again after checking the connection.");
            }
            status(message);
            watchText.setText(message);
        });
    }

    private void status(String value) { if (statusText != null) statusText.setText(value); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

    private static String formatDistance(float meters) {
        if (meters >= 1000) return String.format(Locale.US, "%.1f km", meters / 1000f);
        return String.format(Locale.US, "%.0f m", meters);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
