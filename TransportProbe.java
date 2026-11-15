import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class TransportProbe {
    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "https://api.agnes-ai.cn/v1";
        String key = System.getenv("AGNES_KEY");
        if (key == null || key.isBlank()) { System.out.println("no key env"); return; }
        String body = "{\"model\":\"agnes-2.5-flash\",\"temperature\":0.1,\"messages\":[{\"role\":\"system\",\"content\":\"t\"},{\"role\":\"user\",\"content\":\"x\"}]}";
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUrl.replaceAll("/$", "") + "/chat/completions").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        System.out.println("HTTP " + code);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? connection.getErrorStream() : connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < 4) { System.out.println(line.substring(0, Math.min(180, line.length()))); count++; }
        }
    }
}
