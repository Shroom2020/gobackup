package me.shroom.gobackup;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Paths;

public class HttpMethods {
    // I know some Java expert is going to shoot me for this, but I am mainly a JS dev, and I love my beloved single-line fetch() function.
    // I'm not using a library either because I'm already sick and tired of seeing everything needing more boilerplate

    private final static HttpClient client = HttpClient.newHttpClient();

    public static HttpResponse<String> fetch(String url, String method, String body) {
        try {
            // This is only for generic requests, not the multipart ones needed for uploads
            HttpRequest request = body.length() > 0 ? HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build() : HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();

            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Multipart uploads (dear lord no)
    public static HttpResponse<String> multipartGoFileUpload(String filePath, String token, String containerId, String server) {
        try {
            File file = new File(filePath);
            MultipartBodyPublisher publisher = new MultipartBodyPublisher()
                    .addPart("token", token)
                    .addPart("folderId", containerId)
                    .addPart("file", Paths.get(file.getPath()));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + server + ".gofile.io/uploadFile"))
                    .header("Content-Type", "multipart/form-data; boundary=" + publisher.getBoundary())
                    .POST(publisher.build())
                    .build();

            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}

