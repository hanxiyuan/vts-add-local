import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;

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

public class GenerateRestorePerFolder {

    public static void main(String[] args) throws Exception {
        Map<String, String> env = loadEnv();

        String seatunnelHome = get(env, "SEATUNNEL_HOME", "/opt/seatunnel");
        String milvusUrl = get(env, "MILVUS_URL", "http://192.162.11.52:29530");
        String token = get(env, "TOKEN", "root:Milvus");
        String srcDb = get(env, "SRC_DB", "default");
        String targetDb = get(env, "TARGET_DB", "bak2");
        String outRoot = get(env, "OUT_ROOT", "/data/output");
        int batchSize = Integer.parseInt(get(env, "BATCH_SIZE", "100"));

        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri(milvusUrl)
                .token(token)
                .dbName(srcDb)
                .connectTimeoutMs(30000)
                .build();
        MilvusClientV2 client = new MilvusClientV2(connectConfig);

        Path root = Paths.get(outRoot);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("OUT_ROOT not found: " + outRoot);
        }

        List<Path> dirs;
        try (Stream<Path> s = Files.list(root)) {
            dirs = s.filter(Files::isDirectory).collect(Collectors.toList());
        }
        for (Path dir : dirs) {
            String collection = dir.getFileName().toString();
            Path restoreDir = dir.resolve("restore");
            Files.createDirectories(restoreDir);
            DescribeCollectionResp desc = client.describeCollection(
                    DescribeCollectionReq.builder().collectionName(collection).build());
            List<CreateCollectionReq.FieldSchema> fields = desc.getCollectionSchema().getFieldSchemaList();
            if (fields == null || fields.isEmpty()) {
                System.out.println("skip(no fields): " + collection);
                continue;
            }

            String conf = buildRestoreConf(
                    seatunnelHome, milvusUrl, token, targetDb, batchSize, dir.toString(), collection, fields);
            Path confPath = restoreDir.resolve("restore_" + collection + ".conf");
            Files.writeString(confPath, conf, StandardCharsets.UTF_8);

            String sh = buildRunScript(seatunnelHome, confPath.toString());
            Path shPath = restoreDir.resolve("restore_" + collection + ".sh");
            Files.writeString(shPath, sh, StandardCharsets.UTF_8);
            shPath.toFile().setExecutable(true);

            System.out.println("generated: " + confPath + " and " + shPath);
        }

        client.close();
    }

    private static String buildRunScript(String seatunnelHome, String confPath) {
        return "#!/usr/bin/env bash\n"
                + "set -e\n"
                + seatunnelHome + "/bin/seatunnel.sh --config " + confPath + " -m local\n";
    }

    private static String buildRestoreConf(
            String milvusHome,
            String milvusUrl,
            String token,
            String targetDb,
            int batchSize,
            String path,
            String collection,
            List<CreateCollectionReq.FieldSchema> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("env {\n");
        sb.append("  parallelism = 1\n");
        sb.append("  job.mode = \"BATCH\"\n");
        sb.append("}\n\n");

        sb.append("source {\n");
        sb.append("  LocalFile {\n");
        sb.append("    tables_configs = [\n");
        sb.append("      {\n");
        sb.append("        schema {\n");
        sb.append("          table = \"").append(collection).append("\"\n");
        sb.append("        }\n");
        sb.append("        path = \"").append(path).append("\"\n");
        sb.append("        file_format_type = \"parquet\"\n");
        sb.append("        filename_extension = \"parquet\"\n");
      sb.append("      }\n");
        sb.append("    ]\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        sb.append("sink {\n");
        sb.append("  Milvus {\n");
        sb.append("    url = \"").append(milvusUrl).append("\"\n");
        sb.append("    token = \"").append(token).append("\"\n");
        sb.append("    database = \"").append(targetDb).append("\"\n");
        sb.append("    enable_dynamic_field = false\n");
        sb.append("    schema_save_mode = \"CREATE_SCHEMA_WHEN_NOT_EXIST\"\n");
        sb.append("    batch_size = ").append(batchSize).append("\n");
        sb.append("    field_schema = [\n");

        List<String> fieldBlocks = new ArrayList<>();
        for (CreateCollectionReq.FieldSchema f : fields) {
            // Skip Milvus internal dynamic field and non-user field
            if (f == null || f.getName() == null || "$meta".equals(f.getName())) {
                continue;
            }
            StringBuilder fb = new StringBuilder();
            fb.append("      {\n");
            fb.append("        field_name = \"").append(f.getName()).append("\"\n");
            // LocalFile parquet reader keeps the lower-cased column names written by Parquet sink.
            // Use lower-case source field name to avoid primary-key mapping mismatch (e.g. ID -> id).
            fb.append("        source_field_name = \"")
                    .append(f.getName().toLowerCase())
                    .append("\"\n");
            fb.append("        data_type = ").append(f.getDataType().getCode()).append("\n");

            if (isVectorType(f.getDataType()) && f.getDimension() != null && f.getDimension() > 0) {
                fb.append("        dimension = ").append(f.getDimension()).append("\n");
            }
            if (f.getMaxLength() != null && f.getMaxLength() > 0) {
                fb.append("        max_length = ").append(f.getMaxLength()).append("\n");
            }
            if (f.getElementType() != null) {
                fb.append("        element_type = ").append(f.getElementType().getCode()).append("\n");
            }
            if (f.getMaxCapacity() != null && f.getMaxCapacity() > 0) {
                fb.append("        max_capacity = ").append(f.getMaxCapacity()).append("\n");
            }
            if (Boolean.TRUE.equals(f.getIsPrimaryKey())) {
                fb.append("        is_primary_key = true\n");
            }
            if (Boolean.TRUE.equals(f.getAutoID())) {
                fb.append("        auto_id = true\n");
            }
            if (Boolean.TRUE.equals(f.getIsPartitionKey())) {
                fb.append("        is_partition_key = true\n");
            }
            fb.append("      }");
            fieldBlocks.add(fb.toString());
        }

        sb.append(String.join("\n      ,\n", fieldBlocks)).append("\n");
        sb.append("    ]\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean isVectorType(DataType dt) {
        return dt == DataType.FloatVector
                || dt == DataType.BinaryVector
                || dt == DataType.Float16Vector
                || dt == DataType.BFloat16Vector
                || dt == DataType.SparseFloatVector;
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
