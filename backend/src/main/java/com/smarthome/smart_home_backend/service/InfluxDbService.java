package com.smarthome.smart_home_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class InfluxDbService {

    private static final Logger log = LoggerFactory.getLogger(InfluxDbService.class);

    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient;

    private final String influxUrl;
    private final String influxToken;
    private final String influxOrg;
    private final String influxBucket;

    public InfluxDbService(
            JdbcTemplate jdbcTemplate,
            @Value("${influxdb.url:http://localhost:8086}") String influxUrl,
            @Value("${influxdb.token:da3_super_secret_sensor_telemetry_token_123456789}") String influxToken,
            @Value("${influxdb.org:smarthome_org}") String influxOrg,
            @Value("${influxdb.bucket:sensor_telemetry}") String influxBucket) {
        this.jdbcTemplate = jdbcTemplate;
        this.influxUrl = influxUrl.replaceAll("/$", "");
        this.influxToken = influxToken;
        this.influxOrg = influxOrg;
        this.influxBucket = influxBucket;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    /**
     * Check InfluxDB server health status
     */
    public Map<String, Object> checkHealth() {
        Map<String, Object> response = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(influxUrl + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            response.put("healthy", httpResponse.statusCode() == 200);
            response.put("statusCode", httpResponse.statusCode());
            response.put("latencyMs", latency);
            response.put("url", influxUrl);
            response.put("org", influxOrg);
            response.put("bucket", influxBucket);
            response.put("raw", httpResponse.body());
        } catch (Exception e) {
            response.put("healthy", false);
            response.put("error", e.getMessage());
            response.put("url", influxUrl);
            response.put("latencyMs", System.currentTimeMillis() - start);
        }
        return response;
    }

    /**
     * Execute arbitrary Flux query against InfluxDB
     */
    public Map<String, Object> executeFlux(String fluxQuery) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        if (fluxQuery == null || fluxQuery.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "Flux query string cannot be empty.");
            return result;
        }

        try {
            String queryUri = influxUrl + "/api/v2/query?org=" + influxOrg;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(queryUri))
                    .header("Authorization", "Token " + influxToken)
                    .header("Accept", "application/csv")
                    .header("Content-Type", "application/vnd.flux")
                    .POST(HttpRequest.BodyPublishers.ofString(fluxQuery, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (httpResponse.statusCode() == 200) {
                ParsedFluxCsv parsed = parseFluxCsv(httpResponse.body());
                result.put("success", true);
                result.put("columns", parsed.columns);
                result.put("rows", parsed.rows);
                result.put("rowCount", parsed.rows.size());
                result.put("executionTimeMs", latency);
                result.put("query", fluxQuery);
                result.put("message", "Flux executed successfully in " + latency + " ms.");
            } else {
                result.put("success", false);
                result.put("statusCode", httpResponse.statusCode());
                result.put("message", "InfluxDB Error: " + httpResponse.body());
                result.put("executionTimeMs", latency);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Failed to communicate with InfluxDB: " + e.getMessage());
            result.put("executionTimeMs", System.currentTimeMillis() - start);
        }
        return result;
    }

    /**
     * Write Line Protocol to InfluxDB
     */
    public Map<String, Object> writeLineProtocol(String lineProtocol) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        try {
            String writeUri = influxUrl + "/api/v2/write?org=" + influxOrg + "&bucket=" + influxBucket + "&precision=ns";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(writeUri))
                    .header("Authorization", "Token " + influxToken)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(lineProtocol, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300) {
                result.put("success", true);
                result.put("statusCode", httpResponse.statusCode());
                result.put("executionTimeMs", latency);
                result.put("message", "Successfully ingested points into InfluxDB in " + latency + " ms.");
            } else {
                result.put("success", false);
                result.put("statusCode", httpResponse.statusCode());
                result.put("message", "InfluxDB write error: " + httpResponse.body());
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Write request failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * Fetch formatted telemetry points for charting
     */
    public Map<String, Object> getTelemetryPoints(Long deviceId, String timeRange) {
        String range = (timeRange != null && !timeRange.isBlank()) ? timeRange : "-7d";
        String devFilter = (deviceId != null) ? " and r[\"device_id\"] == \"" + deviceId + "\"" : "";

        String flux = String.format(
                "from(bucket: \"%s\")\n" +
                "  |> range(start: %s)\n" +
                "  |> filter(fn: (r) => r[\"_measurement\"] == \"sensor_reading\"%s)\n" +
                "  |> filter(fn: (r) => r[\"_field\"] == \"value\")\n" +
                "  |> yield(name: \"readings\")",
                influxBucket, range, devFilter
        );

        Map<String, Object> queryRes = executeFlux(flux);
        List<Map<String, Object>> formattedPoints = new ArrayList<>();

        if (Boolean.TRUE.equals(queryRes.get("success"))) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) queryRes.get("rows");
            if (rows != null) {
                for (Map<String, Object> r : rows) {
                    Map<String, Object> point = new HashMap<>();
                    point.put("time", r.get("_time"));
                    point.put("value", r.get("_value"));
                    point.put("deviceId", r.get("device_id"));
                    point.put("deviceName", r.get("device_name"));
                    point.put("unit", r.get("unit") != null ? r.get("unit") : "°C");
                    formattedPoints.add(point);
                }
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", queryRes.get("success"));
        response.put("points", formattedPoints);
        response.put("count", formattedPoints.size());
        response.put("executionTimeMs", queryRes.get("executionTimeMs"));
        return response;
    }

    /**
     * Side-by-Side Live Performance Benchmark (Oracle SQL vs InfluxDB Flux)
     */
    public Map<String, Object> runBenchmark(Long deviceId) {
        long targetDevId = (deviceId != null) ? deviceId : 4L;
        Map<String, Object> benchmark = new LinkedHashMap<>();

        // 1. Oracle SQL Execution
        long oracleStart = System.nanoTime();
        List<Map<String, Object>> oracleRows = Collections.emptyList();
        String oracleSql = "SELECT TRUNC(reading_time, 'HH') AS time_bucket, " +
                "ROUND(AVG(value), 2) AS avg_value, " +
                "COUNT(*) AS sample_count " +
                "FROM sensor_readings " +
                "WHERE device_id = ? " +
                "GROUP BY TRUNC(reading_time, 'HH') " +
                "ORDER BY time_bucket";

        try {
            oracleRows = jdbcTemplate.queryForList(oracleSql, targetDevId);
        } catch (Exception e) {
            log.warn("Oracle benchmark query fallback: {}", e.getMessage());
        }
        double oracleElapsedMs = (System.nanoTime() - oracleStart) / 1_000_000.0;

        // 2. InfluxDB Flux Execution
        String fluxQuery = String.format(
                "from(bucket: \"%s\")\n" +
                "  |> range(start: -30d)\n" +
                "  |> filter(fn: (r) => r[\"_measurement\"] == \"sensor_reading\" and r[\"device_id\"] == \"%d\")\n" +
                "  |> filter(fn: (r) => r[\"_field\"] == \"value\")\n" +
                "  |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)\n" +
                "  |> yield(name: \"hourly_mean\")",
                influxBucket, targetDevId
        );

        long influxStart = System.nanoTime();
        Map<String, Object> influxResult = executeFlux(fluxQuery);
        double influxElapsedMs = (System.nanoTime() - influxStart) / 1_000_000.0;

        // Format benchmark comparison payload
        benchmark.put("targetDeviceId", targetDevId);
        benchmark.put("timestamp", new Date());

        Map<String, Object> oracleCard = new LinkedHashMap<>();
        oracleCard.put("database", "Oracle Database 21c XE");
        oracleCard.put("model", "Relational (3NF + B-Tree Indexes)");
        oracleCard.put("queryLanguage", "SQL");
        oracleCard.put("query", oracleSql.replace("?", String.valueOf(targetDevId)));
        oracleCard.put("latencyMs", Math.round(oracleElapsedMs * 100.0) / 100.0);
        oracleCard.put("rowsReturned", oracleRows.size());
        oracleCard.put("dataPayload", oracleRows);
        oracleCard.put("lockingModel", "Row-level locks / Undo table segments");
        oracleCard.put("compressionEfficiency", "Standard block-level (1.5x - 2x)");

        Map<String, Object> influxCard = new LinkedHashMap<>();
        influxCard.put("database", "InfluxDB 2.7.11");
        influxCard.put("model", "Time-Structured Merge Tree (TSM)");
        influxCard.put("queryLanguage", "Flux");
        influxCard.put("query", fluxQuery);
        influxCard.put("latencyMs", Math.round(influxElapsedMs * 100.0) / 100.0);
        influxCard.put("rowsReturned", influxResult.get("rowCount"));
        influxCard.put("dataPayload", influxResult.get("rows"));
        influxCard.put("lockingModel", "Lock-free append-only memory buffer");
        influxCard.put("compressionEfficiency", "Gorilla Float64 Delta-of-Delta (~10x - 12x)");

        benchmark.put("oracle", oracleCard);
        benchmark.put("influx", influxCard);

        double speedup = (influxElapsedMs > 0) ? (oracleElapsedMs / influxElapsedMs) : 1.0;
        benchmark.put("speedupRatio", Math.round(speedup * 10.0) / 10.0);
        benchmark.put("summary", String.format(
                "InfluxDB executed the analytical downsampling aggregation in %.2f ms vs Oracle's %.2f ms.",
                influxElapsedMs, oracleElapsedMs
        ));

        return benchmark;
    }

    /**
     * Migrate sample 60 sensor readings into InfluxDB
     */
    public Map<String, Object> migrateSampleReadings() {
        Map<String, Object> summary = new LinkedHashMap<>();
        try {
            // Read from Oracle SENSOR_READINGS joined with DEVICES
            String sql = "SELECT s.device_id, s.reading_id, s.value, s.reading_time, " +
                    "d.device_name, d.device_subtype " +
                    "FROM sensor_readings s " +
                    "JOIN devices d ON s.device_id = d.device_id " +
                    "ORDER BY s.reading_time ASC";

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            if (rows.isEmpty()) {
                summary.put("success", false);
                summary.put("message", "No records found in Oracle SENSOR_READINGS table.");
                return summary;
            }

            StringBuilder lineProtocolBatch = new StringBuilder();
            int count = 0;

            for (Map<String, Object> row : rows) {
                long deviceId = ((Number) row.get("DEVICE_ID")).longValue();
                String devName = String.valueOf(row.get("DEVICE_NAME")).replace(" ", "\\ ");
                String devSubtype = String.valueOf(row.get("DEVICE_SUBTYPE"));
                double value = ((Number) row.get("VALUE")).doubleValue();

                java.sql.Timestamp ts = (java.sql.Timestamp) row.get("READING_TIME");
                long epochNs = ts.getTime() * 1_000_000L;

                // Format: sensor_reading,device_id=4,device_name=Kitchen\ Temp\ Sensor,device_subtype=TEMPERATURE_SENSOR,unit=CELSIUS value=23.50 1726599600000000000
                lineProtocolBatch.append(String.format(
                        "sensor_reading,device_id=%d,device_name=%s,device_subtype=%s,unit=CELSIUS value=%.2f %d\n",
                        deviceId, devName, devSubtype, value, epochNs
                ));
                count++;
            }

            Map<String, Object> writeRes = writeLineProtocol(lineProtocolBatch.toString());
            summary.put("success", writeRes.get("success"));
            summary.put("recordsIngested", count);
            summary.put("message", "Migrated " + count + " Oracle sensor records to InfluxDB bucket '" + influxBucket + "'.");
        } catch (Exception e) {
            summary.put("success", false);
            summary.put("error", e.getMessage());
        }
        return summary;
    }

    // Helper: parse InfluxDB CSV table into columns and structured rows
    private ParsedFluxCsv parseFluxCsv(String csv) {
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        if (csv == null || csv.isBlank()) {
            return new ParsedFluxCsv(columns, rows);
        }

        String[] lines = csv.split("\r?\n");
        boolean headerFound = false;

        for (String line : lines) {
            if (line.startsWith("#") || line.trim().isEmpty()) {
                continue;
            }
            String[] tokens = line.split(",", -1);
            if (!headerFound) {
                // Header row
                for (String token : tokens) {
                    columns.add(token.trim());
                }
                headerFound = true;
            } else {
                // Data row
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < tokens.length && i < columns.size(); i++) {
                    String col = columns.get(i);
                    String val = tokens[i].trim();
                    if (!col.isEmpty() && !col.equals("result") && !col.equals("table")) {
                        row.put(col, val);
                    }
                }
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        }

        // Clean columns list for display
        List<String> cleanCols = columns.stream()
                .filter(c -> !c.isEmpty() && !c.equals("result") && !c.equals("table"))
                .toList();

        return new ParsedFluxCsv(cleanCols, rows);
    }

    private static class ParsedFluxCsv {
        final List<String> columns;
        final List<Map<String, Object>> rows;

        ParsedFluxCsv(List<String> columns, List<Map<String, Object>> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }
}
