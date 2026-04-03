import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public class BuildIndexAndLoad {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = loadEnv();
        String milvusUrl = get(env, "MILVUS_URL", "http://192.162.11.52:29530");
        String token = get(env, "TOKEN", "root:Milvus");
        String targetDb = get(env, "TARGET_DB", "bak2");
        String outRoot = get(env, "OUT_ROOT", "/data/output");

        if (args.length < 1 || args[0] == null || args[0].isEmpty()) {
            throw new IllegalArgumentException("Usage: java BuildIndexAndLoad <collection_name>");
        }
        String collection = args[0];

        Path idxFile = Paths.get(outRoot, collection, "_indexes.json");
        if (!Files.exists(idxFile)) {
            System.out.println("skip(no index json): " + idxFile);
            return;
        }

        String txt = Files.readString(idxFile, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(txt).getAsJsonObject();
        JsonArray arr = root.has("data") && root.get("data").isJsonArray()
                ? root.getAsJsonArray("data")
                : new JsonArray();

        HttpClient client = HttpClient.newHttpClient();
        Gson gson = new Gson();

        for (JsonElement e : arr) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject idx = e.getAsJsonObject();

            JsonObject req = new JsonObject();
            req.addProperty("dbName", targetDb);
            req.addProperty("collectionName", collection);
            JsonArray params = new JsonArray();
            params.add(idx);
            req.add("indexParams", params);

            String resp = postJson(client, milvusUrl + "/v2/vectordb/indexes/create", token, gson.toJson(req));
            System.out.println("index resp(" + collection + "): " + resp);
        }

        JsonObject loadReq = new JsonObject();
        loadReq.addProperty("dbName", targetDb);
        loadReq.addProperty("collectionName", collection);
        String loadResp = postJson(client, milvusUrl + "/v2/vectordb/collections/load", token, gson.toJson(loadReq));
        System.out.println("load resp(" + collection + "): " + loadResp);
    }

    private static String postJson(HttpClient client, String url, String token, String body)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.body();
    }

    private static Map<String, String> loadEnv() throws IOException {
        Path envPath = Paths.get("config/.env");
        if (!Files.exists(envPath)) {
            envPath = Paths.get(".env");
        }
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.exists(envPath)) {
            return map;
        }
        for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int idx = s.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            map.put(s.substring(0, idx).trim(), s.substring(idx + 1).trim());
        }
        return map;
    }

    private static String get(Map<String, String> env, String key, String defVal) {
        String v = env.get(key);
        if (v == null || v.isEmpty()) {
            return defVal;
        }
        return v;
    }
}
