import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.stream.Stream;

public class DumpMilvusSchemaSidecar {
    static String esc(String s){ return s.replace("\\","\\\\").replace("\"","\\\""); }

    public static void main(String[] args) throws Exception {
        String milvusUrl = "http://192.162.11.52:29530";
        String token = "root:Milvus";
        String db = "default";         // 导出源库
        Path outRoot = Paths.get("/data/output");

        HttpClient client = HttpClient.newHttpClient();

        try (Stream<Path> ds = Files.list(outRoot)) {
            ds.filter(Files::isDirectory).forEach(dir -> {
                String col = dir.getFileName().toString();
                String body = "{\"dbName\":\"" + esc(db) + "\",\"collectionName\":\"" + esc(col) + "\"}";
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(milvusUrl + "/v2/vectordb/collections/describe"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
                try {
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    Path out = dir.resolve("_milvus_schema.json");
                    Files.writeString(out, resp.body(), StandardCharsets.UTF_8);
                    System.out.println("written: " + out + " status=" + resp.statusCode());
                } catch (Exception e) {
                    System.err.println("skip " + col + ": " + e.getMessage());
                }
            });
        }
    }
}
