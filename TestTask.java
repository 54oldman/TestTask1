
import .time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Queue;
import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;


public class CrptApi {

    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final Queue<Instant> requestQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ReentrantLock lock = new ReentrantLock();

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;

        scheduler.scheduleAtFixedRate(this::cleanupQueue, 0, 1, timeUnit);
    }

    private void cleanupQueue() {
        Instant now = Instant.now();
        Instant threshold = now.minus(1, timeUnit.toChronoUnit());
        requestQueue.removeIf(instant -> instant.isBefore(threshold));
    }


    public String createDocument(Document document, String signature) throws InterruptedException, IOException {
        lock.lockInterruptibly();
        try {

            while (requestQueue.size() >= requestLimit) {
                Thread.sleep(10);
            }
            requestQueue.add(Instant.now());
            return makeApiCall(document, signature);
        }
        finally {
            lock.unlock();
        }
    }

    private String makeApiCall(Document document, String signature) throws IOException {
        Gson gson = new Gson();
        String requestBody = gson.toJson(new ApiRequest(document, signature));

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                throw new IOException("API call failed with status code: " + response.statusCode() + ", body: " + response.body());
            }

        } catch (InterruptedException e) {
            throw new IOException("API call interrupted", e);
        }
    }


    static class Document {
        String description;
        String participantInn;
        String docId;
        String docStatus;
        String docType;
        String importRequest;
        String productionDate;
        String productionType;

        public Document(String description, String participantInn, String docId, String docStatus, String docType, String importRequest, String productionDate, String productionType) {
            this.description = description;
            this.participantInn = participantInn;
            this.docId = docId;
            this.docStatus = docStatus;
            this.docType = docType;
            this.importRequest = importRequest;
            this.productionDate = productionDate;
            this.productionType = productionType;
        }

    }

    static class ApiRequest {
        private final Document document;
        private final String signature;

        public ApiRequest(Document document, String signature) {
            this.document = document;
            this.signature = signature;
        }

        public Document getDocument() {
            return document;
        }

        public String getSignature() {
            return signature;
        }

    }

    public static void main(String[] args) throws InterruptedException, IOException {
        CrptApi api = new CrptApi(TimeUnit.SECONDS, 5);
        Document doc = new Document(
                "Test document",
                "1234567890",
                "DOC-123",
                "NEW",
                "LP_INTRODUCE_GOODS",
                false +"",
                "2023-10-26",
                "OWN_PRODUCTION");

        String signature = "someDigitalSignature";

        for (int i = 0; i < 10; i++) {
            try {
                String result = api.createDocument(doc, signature);
                System.out.println("Request " + (i + 1) + " Result: " + result);
            } catch (InterruptedException | IOException e) {
                System.err.println("Request " + (i + 1) + " Error: " + e.getMessage());
            }
        }

        Thread.sleep(3000);
        api.scheduler.shutdown();
        api.scheduler.awaitTermination(5, TimeUnit.SECONDS);


    }
}
