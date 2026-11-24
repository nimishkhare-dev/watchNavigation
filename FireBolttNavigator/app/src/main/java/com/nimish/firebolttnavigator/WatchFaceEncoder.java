package com.nimish.firebolttnavigator;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Builds a documented MoYoung Type-C face containing a route overview bitmap. */
public final class WatchFaceEncoder {
    public static final int WIDTH = 368;
    public static final int HEIGHT = 448;
    private static final int HEADER_SIZE = 1900;
    private static final SecureRandom RANDOM = new SecureRandom();

    private WatchFaceEncoder() {}

    public static final class BuildResult {
        public final byte[] faceFile;
        public final Bitmap preview;
        public final boolean backgroundRle;
        public final boolean previewRle;
        public final Bitmap fullImage;
        BuildResult(byte[] faceFile, Bitmap preview, boolean backgroundRle,
                    boolean previewRle, Bitmap fullImage) {
            this.faceFile = faceFile;
            this.preview = preview;
            this.backgroundRle = backgroundRle;
            this.previewRle = previewRle;
            this.fullImage = fullImage;
        }
    }

    public static BuildResult build(RouteEngine.Route route, double currentLat, double currentLon,
                                    String nextInstruction, String turnDistance) throws IOException {
        Bitmap full = drawRoute(route, currentLat, currentLon, nextInstruction, turnDistance, WIDTH, HEIGHT);
        Bitmap thumb = Bitmap.createScaledBitmap(full, 140, 163, true);
        EncodedBlob bg = encodeBitmap(full);
        EncodedBlob preview = encodeBitmap(thumb);

        byte[] header = new byte[HEADER_SIZE];
        header[0] = (byte) 0x81; // MoYoung Type B/C file ID; Type-C layout follows dawft.
        header[1] = 1;           // one full-screen BACKGROUND entry
        header[2] = 2;           // background + gallery preview blob
        putU16LE(header, 3, 50000 + RANDOM.nextInt(15000));

        // Type-C FaceData entry: [field type, blob index, x, y, width, height].
        header[5] = 0x01;
        header[6] = 0x00;
        putU16LE(header, 7, 0);
        putU16LE(header, 9, 0);
        putU16LE(header, 11, WIDTH);
        putU16LE(header, 13, HEIGHT);

        // Type-C offset table begins at byte 400; offsets are relative to byte 1900.
        putU32LE(header, 400, 0);
        putU32LE(header, 404, bg.bytes.length);

        ByteArrayOutputStream file = new ByteArrayOutputStream(HEADER_SIZE + bg.bytes.length + preview.bytes.length);
        file.write(header);
        file.write(bg.bytes);
        file.write(preview.bytes);
        return new BuildResult(file.toByteArray(), thumb, bg.rle, preview.rle, full);
    }

    private static final class EncodedBlob {
        final byte[] bytes;
        final boolean rle;
        EncodedBlob(byte[] bytes, boolean rle) { this.bytes = bytes; this.rle = rle; }
    }

    private static EncodedBlob encodeBitmap(Bitmap bitmap) throws IOException {
        byte[] raw = rgb565BigEndian(bitmap);
        byte[] compressed = rleLine(raw, bitmap.getWidth(), bitmap.getHeight());
        if (compressed != null && compressed.length < raw.length) return new EncodedBlob(compressed, true);
        return new EncodedBlob(raw, false);
    }

    private static byte[] rgb565BigEndian(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] out = new byte[pixels.length * 2];
        int p = 0;
        for (int color : pixels) {
            int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
            int value = ((red >> 3) << 11) | ((green >> 2) << 5) | (blue >> 3);
            if (value == 0x0821) value = 0x0822; // reserved RLE marker in dawft Type-C blobs.
            out[p++] = (byte) (value >>> 8);
            out[p++] = (byte) value;
        }
        return out;
    }

    private static byte[] rleLine(byte[] raw, int width, int height) throws IOException {
        int prefix = 2 + (height * 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(raw.length, 60000));
        out.write(0x08);
        out.write(0x21);
        for (int i = 0; i < height * 2; i++) out.write(0);
        int offset = prefix;

        for (int y = 0; y < height; y++) {
            int row = y * width * 2;
            int previousHigh = raw[row] & 0xff;
            int previousLow = raw[row + 1] & 0xff;
            int run = 1;
            for (int x = 1; x < width; x++) {
                int high = raw[row + x * 2] & 0xff;
                int low = raw[row + x * 2 + 1] & 0xff;
                if (high == previousHigh && low == previousLow) {
                    run++;
                    if (run == 255) {
                        out.write(previousHigh); out.write(previousLow); out.write(255);
                        offset += 3;
                        run = 0;
                    }
                } else {
                    if (run > 0) {
                        out.write(previousHigh); out.write(previousLow); out.write(run);
                        offset += 3;
                    }
                    previousHigh = high; previousLow = low; run = 1;
                }
            }
            if (run > 0) {
                out.write(previousHigh); out.write(previousLow); out.write(run);
                offset += 3;
            }
            if (offset > 0xffff) return null;
            byte[] bytes = out.toByteArray();
            bytes[2 + y * 2] = (byte) offset;
            bytes[3 + y * 2] = (byte) (offset >>> 8);
            // Rewrite modified offset table without rebuilding the whole stream.
            // The stream is still small (the 16-bit offset cap is 64 KiB).
            out.reset();
            out.write(bytes);
        }
        byte[] result = out.toByteArray();
        if (result.length >= raw.length) return null;
        return result;
    }

    private static void putU16LE(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private static void putU32LE(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static Bitmap drawRoute(RouteEngine.Route route, double currentLat, double currentLon,
                                    String nextInstruction, String turnDistance, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(9, 18, 32));

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(94, 234, 212)); p.setTextSize(15); p.setFakeBoldText(true);
        canvas.drawText("FIREBOLTT NAV", 18, 27, p);
        p.setColor(Color.rgb(148, 163, 184)); p.setTextSize(11); p.setFakeBoldText(false);
        canvas.drawText("NEAR-FIELD NAVIGATION  •  100 M AHEAD", 18, 45, p);
        canvas.drawLine(16, 56, width - 16, 56, linePaint(Color.rgb(46, 64, 86), 1.2f));

        String destination = route.destination == null ? "Destination" : route.destination.name;
        destination = shorten(destination, 32);
        p.setColor(Color.WHITE); p.setTextSize(13); p.setFakeBoldText(true);
        canvas.drawText("TO  " + destination, 18, 78, p);
        p.setColor(Color.rgb(148, 163, 184)); p.setTextSize(11); p.setFakeBoldText(false);
        canvas.drawText(route.distanceLabel() + "  •  " + route.durationLabel(), 18, 96, p);

        RectF mapBox = new RectF(14, 112, width - 14, 330);
        p.setColor(Color.rgb(17, 31, 49)); p.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(mapBox, 15, 15, p);
        p.setColor(Color.rgb(34, 52, 72)); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1);
        for (int x = 35; x < width - 20; x += 42) canvas.drawLine(x, mapBox.top + 8, x, mapBox.bottom - 8, p);
        for (int y = 130; y < 330; y += 38) canvas.drawLine(22, y, width - 22, y, p);
        p.setStyle(Paint.Style.FILL);

        List<RouteEngine.Point> allPoints = route.geometry;
        if (allPoints != null && !allPoints.isEmpty()) {
            /*
             * Fixed near-field viewport:
             * - about 100 m of route ahead of the live GPS fix
             * - 20 m behind the fix for context
             * - 35 m either side of the fix
             * The map is not fitted to the whole trip or to the route's overall bounds.
             */
            final double viewLeftMeters = -90.0;
            final double viewRightMeters = 90.0;
            final double viewBottomMeters = -20.0;
            final double viewTopMeters = 100.0;

            double safeLat = Double.isFinite(currentLat) ? currentLat : route.origin.latitude;
            double safeLon = Double.isFinite(currentLon) ? currentLon : route.origin.longitude;
            double longitudeMetersPerDegree = 111320.0 * Math.max(0.15,
                    Math.cos(Math.toRadians(safeLat)));

            int nearest = 0;
            double nearestDistance = Double.MAX_VALUE;
            for (int i = 0; i < allPoints.size(); i++) {
                RouteEngine.Point point = allPoints.get(i);
                double distance = haversineMeters(safeLat, safeLon, point.latitude, point.longitude);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = i;
                }
            }

            // Convert only the route immediately ahead into local metres.
            List<RouteEngine.Point> visible = new ArrayList<>();
            visible.add(new RouteEngine.Point(safeLat, safeLon));
            RouteEngine.Point previous = allPoints.get(nearest);
            double travelledAhead = 0.0;
            for (int i = nearest; i < allPoints.size(); i++) {
                RouteEngine.Point point = allPoints.get(i);
                if (i > nearest) {
                    travelledAhead += haversineMeters(previous.latitude, previous.longitude,
                            point.latitude, point.longitude);
                }
                visible.add(point);
                previous = point;
                if (travelledAhead >= 100.0) break;
            }

            float pad = 12f;
            float usableW = mapBox.width() - 2f * pad;
            float usableH = mapBox.height() - 2f * pad;
            double metresWide = viewRightMeters - viewLeftMeters;
            double metresHigh = viewTopMeters - viewBottomMeters;
            float scale = (float) Math.min(usableW / metresWide, usableH / metresHigh);
            float drawnW = (float) (metresWide * scale);
            float drawnH = (float) (metresHigh * scale);
            float left = mapBox.centerX() - drawnW / 2f;
            float top = mapBox.centerY() - drawnH / 2f;

            // Subtle fixed 20 m grid makes the very local scale easier to read.
            p.setColor(Color.rgb(31, 48, 67));
            p.setStrokeWidth(1f);
            p.setStyle(Paint.Style.STROKE);
            for (int metres = -20; metres <= 100; metres += 20) {
                float y = top + (float) ((viewTopMeters - metres) * scale);
                canvas.drawLine(left, y, left + drawnW, y, p);
            }
            for (int metres = -20; metres <= 20; metres += 20) {
                float x = left + (float) ((metres - viewLeftMeters) * scale);
                canvas.drawLine(x, top, x, top + drawnH, p);
            }
            p.setStyle(Paint.Style.FILL);

            Path path = new Path();
            boolean started = false;
            for (RouteEngine.Point point : visible) {
                double xMeters = (point.longitude - safeLon) * longitudeMetersPerDegree;
                double yMeters = (point.latitude - safeLat) * 110540.0;
                float x = left + (float) ((xMeters - viewLeftMeters) * scale);
                float y = top + (float) ((viewTopMeters - yMeters) * scale);
                if (!started) {
                    path.moveTo(x, y);
                    started = true;
                } else {
                    path.lineTo(x, y);
                }
            }

            // Clip to the near-field viewport; the rest of the journey is intentionally hidden.
            int save = canvas.save();
            canvas.clipRect(mapBox);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(Color.rgb(3, 10, 18));
            p.setStrokeWidth(12);
            canvas.drawPath(path, p);
            p.setColor(Color.rgb(45, 212, 191));
            p.setStrokeWidth(6);
            canvas.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);

            // Highlight the current GPS fix; destination is shown only if it is within this viewport.
            float currentX = left + (float) ((0.0 - viewLeftMeters) * scale);
            float currentY = top + (float) ((viewTopMeters - 0.0) * scale);
            drawDot(canvas, currentX, currentY, Color.rgb(59, 130, 246), 9);

            RouteEngine.Point destinationPoint = allPoints.get(allPoints.size() - 1);
            double destinationX = (destinationPoint.longitude - safeLon) * longitudeMetersPerDegree;
            double destinationY = (destinationPoint.latitude - safeLat) * 110540.0;
            if (destinationX >= viewLeftMeters && destinationX <= viewRightMeters
                    && destinationY >= viewBottomMeters && destinationY <= viewTopMeters) {
                float x = left + (float) ((destinationX - viewLeftMeters) * scale);
                float y = top + (float) ((viewTopMeters - destinationY) * scale);
                drawDot(canvas, x, y, Color.rgb(239, 68, 68), 7);
            }
            canvas.restoreToCount(save);
        }

        canvas.drawLine(16, 344, width - 16, 344, linePaint(Color.rgb(46, 64, 86), 1.2f));
        p.setColor(Color.rgb(94, 234, 212)); p.setTextSize(11); p.setFakeBoldText(true);
        canvas.drawText("NEXT", 18, 365, p);
        p.setColor(Color.WHITE); p.setTextSize(15); p.setFakeBoldText(true);
        drawWrapped(canvas, shorten(nextInstruction == null ? "Follow the route" : nextInstruction, 75), 18, 389, width - 36, p, 19, 2);
        p.setColor(Color.rgb(148, 163, 184)); p.setTextSize(11); p.setFakeBoldText(false);
        canvas.drawText(turnDistance == null ? "Follow the highlighted line" : turnDistance, 18, 434, p);
        return bitmap;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        if (!Double.isFinite(lat1) || !Double.isFinite(lon1)) return Double.MAX_VALUE;
        double earth = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earth * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static Paint linePaint(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStrokeWidth(width); return p;
    }

    private static void drawDot(Canvas canvas, float x, float y, int color, float radius) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE); canvas.drawCircle(x, y, radius + 3, p);
        p.setColor(color); canvas.drawCircle(x, y, radius, p);
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static void drawWrapped(Canvas canvas, String text, float x, float y, float maxWidth,
                                    Paint paint, float lineHeight, int maxLines) {
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        int lineCount = 0;
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(candidate) > maxWidth && line.length() > 0) {
                canvas.drawText(line.toString(), x, y + lineCount * lineHeight, paint);
                lineCount++;
                line.setLength(0);
                line.append(word);
                if (lineCount >= maxLines) return;
            } else {
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
        }
        if (line.length() > 0 && lineCount < maxLines) canvas.drawText(line.toString(), x, y + lineCount * lineHeight, paint);
    }
}
