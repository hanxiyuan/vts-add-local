import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ExportIndexJson {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = loadEnv();
        String milvusUrl = get(env, "MILVUS_URL", "http://192.162.11.52:29530");
        String token = get(env, "TOKEN", "root:Milvus");
        String srcDb = get(env, "SRC_DB", "default");
        String outRoot = get(env, "OUT_ROOT", "/data/output");

        ConnectConfig cfg = ConnectConfig.builder()
                .uri(milvusUrl)
                .token(token)
                .dbName(srcDb)
                .connectTimeoutMs(30000)
                .build();
        MilvusClientV2 client = new MilvusClientV2(cfg);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        List<Path> dirs;
        try (Stream<Path> s = Files.list(Paths.get(outRoot))) {
            dirs = s.filter(Files::isDirectory).collect(Collectors.toList());
        }

        for (Path dir : dirs) {
            String collection = dir.getFileName().toString();
            List<Map<String, Object>> data = new ArrayList<>();

            List<String> indexNames = client.listIndexes(
                    ListIndexesReq.builder().collectionName(collection).build());
            for (String idxName : indexNames) {
                DescribeIndexResp resp = client.describeIndex(
                        DescribeIndexReq.builder().collectionName(collection).indexName(idxName).build());
                for (DescribeIndexResp.IndexDesc d : resp.getIndexDescriptions()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("fieldName", d.getFieldName());
                    item.put("indexName", d.getIndexName());
                    item.put("indexType", d.getIndexType().getName());
                    if (d.getMetricType() != null && d.getMetricType() != IndexParam.MetricType.INVALID) {
                        item.put("metricType", d.getMetricType().name());
                    }
                    if (d.getExtraParams() != null && !d.getExtraParams().isEmpty()) {
                        item.put("extraParams", d.getExtraParams());
                    }
                    data.add(item);
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("collection", collection);
            out.put("source_db", srcDb);
            out.put("data", data);

            Path outFile = dir.resolve("_indexes.json");
            Files.writeString(outFile, gson.toJson(out), StandardCharsets.UTF_8);
            System.out.println("written: " + outFile);
        }

        client.close();
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
