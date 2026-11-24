package com.nimish.firebolttnavigator;

import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small, keyless prototype client for Nominatim + OSRM. */
public final class RouteEngine {
    private static final String USER_AGENT = "FireBolttNavigator/0.1 (Android personal navigation prototype)";
    private static long lastGeocodeRequestMs = 0L;

    private RouteEngine() {}

    public static final class Place {
        public final String name;
        public final double latitude;
        public final double longitude;

        public Place(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        @Override public String toString() { return name; }
    }

    public static final class Point {
        public final double latitude;
        public final double longitude;
        public Point(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public static final class Step {
        public final String instruction;
        public final String roadName;
        public final String maneuverType;
        public final String modifier;
        public final double distanceMeters;
        public final double latitude;
        public final double longitude;

        public Step(String instruction, String roadName, String maneuverType, String modifier,
                    double distanceMeters, double latitude, double longitude) {
            this.instruction = instruction;
            this.roadName = roadName;
            this.maneuverType = maneuverType;
            this.modifier = modifier;
            this.distanceMeters = distanceMeters;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public static final class Route {
        public final List<Point> geometry;
        public final List<Step> steps;
        public final double distanceMeters;
        public final double durationSeconds;
        public final String profile;
        public final Place destination;
        public final Point origin;

        public Route(List<Point> geometry, List<Step> steps, double distanceMeters,
                     double durationSeconds, String profile, Place destination, Point origin) {
            this.geometry = geometry;
            this.steps = steps;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.profile = profile;
            this.destination = destination;
            this.origin = origin;
        }

        public String distanceLabel() {
            if (distanceMeters >= 1000) return String.format(Locale.US, "%.1f km", distanceMeters / 1000.0);
            return String.format(Locale.US, "%.0f m", distanceMeters);
        }

        public String durationLabel() {
            long minutes = Math.max(1, Math.round(durationSeconds / 60.0));
            if (minutes >= 60) return String.format(Locale.US, "%dh %02dm", minutes / 60, minutes % 60);
            return minutes + " min";
        }
    }

    public static List<Place> searchPlaces(String query) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.length() < 3) throw new IllegalArgumentException("Enter at least 3 characters for the destination.");
        synchronized (RouteEngine.class) {
            long wait = 1100L - (System.currentTimeMillis() - lastGeocodeRequestMs);
            if (wait > 0) Thread.sleep(wait);
            lastGeocodeRequestMs = System.currentTimeMillis();
        }
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8.name());
        String body = get(url);
        JSONArray array = new JSONArray(body);
        List<Place> places = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            places.add(new Place(item.optString("display_name", q),
                    Double.parseDouble(item.getString("lat")),
                    Double.parseDouble(item.getString("lon"))));
        }
        return places;
    }

    public static Route getRoute(Location origin, Place destination, String profile) throws Exception {
        if (origin == null || destination == null) throw new IllegalArgumentException("Current location and destination are required.");
        String safeProfile = "cycling".equals(profile) || "walking".equals(profile) ? profile : "driving";
        String coordinates = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f",
                origin.getLongitude(), origin.getLatitude(), destination.longitude, destination.latitude);
        String url = "https://router.project-osrm.org/route/v1/" + safeProfile + "/" + coordinates
                + "?overview=full&steps=true&geometries=geojson&alternatives=false";
        JSONObject root = new JSONObject(get(url));
        if (!"Ok".equals(root.optString("code"))) {
            throw new IllegalStateException(root.optString("message", "Routing service could not find a route."));
        }
        JSONArray routes = root.getJSONArray("routes");
        if (routes.length() == 0) throw new IllegalStateException("No route found for that destination.");
        JSONObject routeJson = routes.getJSONObject(0);

        JSONObject geometry = routeJson.getJSONObject("geometry");
        JSONArray coordinatesArray = geometry.getJSONArray("coordinates");
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < coordinatesArray.length(); i++) {
            JSONArray pair = coordinatesArray.getJSONArray(i);
            points.add(new Point(pair.getDouble(1), pair.getDouble(0)));
        }

        List<Step> routeSteps = new ArrayList<>();
        JSONArray legs = routeJson.getJSONArray("legs");
        for (int l = 0; l < legs.length(); l++) {
            JSONArray steps = legs.getJSONObject(l).getJSONArray("steps");
            for (int s = 0; s < steps.length(); s++) {
                JSONObject item = steps.getJSONObject(s);
                JSONObject maneuver = item.getJSONObject("maneuver");
                JSONArray loc = maneuver.getJSONArray("location");
                String type = maneuver.optString("type", "continue");
                String modifier = maneuver.optString("modifier", "");
                String road = item.optString("name", "");
                String instruction = makeInstruction(type, modifier, road);
                routeSteps.add(new Step(instruction, road, type, modifier,
                        item.optDouble("distance", 0), loc.getDouble(1), loc.getDouble(0)));
            }
        }
        return new Route(points, routeSteps, routeJson.optDouble("distance", 0),
                routeJson.optDouble("duration", 0), safeProfile, destination,
                new Point(origin.getLatitude(), origin.getLongitude()));
    }

    private static String makeInstruction(String type, String modifier, String road) {
        String action;
        switch (type) {
            case "depart": action = "Start"; break;
            case "arrive": action = "You have arrived"; break;
            case "turn": action = "Turn " + modifier; break;
            case "new name": action = "Continue"; break;
            case "merge": action = "Merge"; break;
            case "on ramp": action = "Take the ramp"; break;
            case "off ramp": action = "Take the exit"; break;
            case "fork": action = "Keep " + modifier; break;
            case "end of road": action = "At the end of the road, turn " + modifier; break;
            case "roundabout":
            case "rotary": action = "Enter the roundabout"; break;
            case "uturn": action = "Make a U-turn"; break;
            default: action = "Continue"; break;
        }
        if (road != null && !road.trim().isEmpty() && !"You have arrived".equals(action) && !"Start".equals(action)) {
            action += " onto " + road.trim();
        }
        return action;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String text = readAll(stream);
            if (code < 200 || code >= 300) throw new IllegalStateException("Map service HTTP " + code + ": " + text);
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
