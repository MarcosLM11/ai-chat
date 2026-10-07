package com.example.aichat.eval;

import com.example.aichat.chat.RagProperties;
import com.example.aichat.eval.RetrievalEvaluator.EvalQuestion;
import com.example.aichat.eval.RetrievalEvaluator.IndexedChunk;
import com.example.aichat.eval.RetrievalEvaluator.QuestionResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.example.aichat.eval.RetrievalEvaluator.hitRate;
import static com.example.aichat.eval.RetrievalEvaluator.isAnswerable;
import static com.example.aichat.eval.RetrievalEvaluator.isHit;
import static com.example.aichat.eval.RetrievalEvaluator.mrr;
import static com.example.aichat.eval.RetrievalEvaluator.normalize;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("eval")
@SpringBootTest
class ChunkSizeEvaluationTest {
    private static final String TABLE_PREFIX = "eval_chunks_";
    private static final int MAX_K = 10;
    private static final int MAX_CHUNKS = 100_000;

    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private RagProperties ragProperties;

    @Value("${eval.chunk-sizes:128,192,256,320,384,448,500,768}")
    private List<Integer> chunkSizes;
    @Value("${eval.context-budget:4000}")
    private int contextBudget;
    @Value("${spring.ai.ollama.base-url}")
    private String ollamaBaseUrl;
    @Value("${spring.ai.ollama.embedding.model}")
    private String embeddingModelName;
    @Value("${spring.ai.vectorstore.pgvector.dimensions}")
    private int dimensions;

    @Test
    void compareChunkSizes() throws IOException {
        var dataset = RetrievalEvaluator.loadDataset(jsonMapper);
        var sources = loadSources(dataset);
        var ollama = RestClient.builder().baseUrl(ollamaBaseUrl).build();

        var runs = new ArrayList<ChunkSizeRun>();
        for (int chunkSize : chunkSizes) {
            var run = evaluate(chunkSize, dataset, sources, ollama);
            runs.add(run);
            System.out.printf("chunk-size %d done: %d chunks, hit@%d %.3f%n",
                    chunkSize, run.chunks(), ragProperties.topK(), run.hitRateAtTopK());
        }

        print(runs);
        var report = new ChunkSizeReport(LocalDateTime.now().toString(), embeddingModelName, ragProperties.topK(),
                contextBudget, runs);
        var reportFile = RetrievalEvaluator.writeReport(jsonMapper, "chunk-size", report);
        System.out.println("Report written to " + reportFile.toAbsolutePath());
    }

    private List<Document> loadSources(List<EvalQuestion> dataset) {
        var fileNames = dataset.stream().map(EvalQuestion::source).filter(Objects::nonNull).distinct().toList();
        var documents = new ArrayList<Document>();
        for (var fileName : fileNames) {
            var data = jdbcClient.sql("SELECT data FROM documents WHERE name = ? ORDER BY uploaded_at DESC LIMIT 1")
                    .param(fileName)
                    .query(byte[].class)
                    .optional();
            assertThat(data).as("Document %s must be uploaded", fileName).isPresent();
            var read = new TikaDocumentReader(new ByteArrayResource(data.get())).read();
            read.forEach(document -> document.getMetadata().put("fileName", fileName));
            documents.addAll(read);
        }
        return documents;
    }

    private ChunkSizeRun evaluate(int chunkSize, List<EvalQuestion> dataset, List<Document> sources, RestClient ollama) {
        var table = TABLE_PREFIX + chunkSize;
        var started = System.nanoTime();
        try {
            var chunks = splitter(chunkSize).apply(sources);
            var truncated = chunks.stream().filter(chunk -> exceedsEmbeddingContext(ollama, chunk.getText())).count();

            var vectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                    .vectorTableName(table)
                    .dimensions(dimensions)
                    .distanceType(PgDistanceType.COSINE_DISTANCE)
                    .indexType(PgIndexType.HNSW)
                    .initializeSchema(true)
                    .removeExistingVectorStoreTable(true)
                    .build();
            vectorStore.afterPropertiesSet();
            vectorStore.add(chunks);

            var indexed = chunks.stream()
                    .map(chunk -> new IndexedChunk((String) chunk.getMetadata().get("fileName"), normalize(chunk.getText())))
                    .toList();
            var budgetK = Math.max(1, contextBudget / chunkSize);
            var results = RetrievalEvaluator.evaluate(dataset, vectorStore, indexed, Math.max(MAX_K, budgetK));
            var answerable = results.stream().filter(RetrievalEvaluator::isAnswerable).toList();

            return new ChunkSizeRun(
                    chunkSize,
                    chunks.size(),
                    truncated / (double) chunks.size(),
                    answerable.size(),
                    answerable.stream().filter(r -> !r.reachable()).map(QuestionResult::id).toList(),
                    hitRate(answerable, 1),
                    hitRate(answerable, 3),
                    hitRate(answerable, ragProperties.topK()),
                    hitRate(answerable, MAX_K),
                    mrr(answerable.stream().map(r -> limit(r, MAX_K)).toList()),
                    budgetK,
                    hitRate(answerable, budgetK),
                    answerable.stream().filter(r -> !isHit(r, ragProperties.topK())).map(QuestionResult::id).toList(),
                    Duration.ofNanos(System.nanoTime() - started).toSeconds());
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private TokenTextSplitter splitter(int chunkSize) {
        return TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(MAX_CHUNKS)
                .withKeepSeparator(true)
                .build();
    }

    private boolean exceedsEmbeddingContext(RestClient ollama, String text) {
        try {
            ollama.post()
                    .uri("/api/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", embeddingModelName, "input", text, "truncate", false))
                    .retrieve()
                    .toBodilessEntity();
            return false;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError() || e.getStatusCode().is5xxServerError()) {
                return true;
            }
            throw e;
        }
    }

    private QuestionResult limit(QuestionResult result, int k) {
        return isHit(result, k) ? result : new QuestionResult(result.id(), result.category(), result.question(),
                result.reachable(), null, result.hits());
    }

    private void print(List<ChunkSizeRun> runs) {
        var topK = ragProperties.topK();
        var out = new StringBuilder();
        out.append("%n=== Chunk size evaluation (%s) ===%n".formatted(embeddingModelName));
        out.append("Hit rate over %d answerable questions; budget = top-k that fits %d tokens of context%n%n"
                .formatted(runs.getFirst().answerable(), contextBudget));
        out.append("%6s %7s %10s %7s %7s %7s %7s %7s %12s %7s%n".formatted(
                "size", "chunks", "truncated", "@1", "@3", "@" + topK, "@" + MAX_K, "MRR", "budget(k)", "time"));
        for (var run : runs) {
            out.append("%6d %7d %9.1f%% %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f(%2d) %6ds%n".formatted(
                    run.chunkSize(), run.chunks(), run.truncatedRatio() * 100, run.hitRateAt1(), run.hitRateAt3(),
                    run.hitRateAtTopK(), run.hitRateAt10(), run.mrr(), run.hitRateAtBudget(), run.budgetK(),
                    run.seconds()));
        }
        out.append(System.lineSeparator());
        runs.stream()
                .filter(run -> !run.splitEvidence().isEmpty())
                .forEach(run -> out.append("size %d: evidence split across chunks in %s%n"
                        .formatted(run.chunkSize(), run.splitEvidence())));
        System.out.println(out);
    }

    record ChunkSizeRun(int chunkSize, int chunks, double truncatedRatio, int answerable, List<String> splitEvidence,
                        double hitRateAt1, double hitRateAt3, double hitRateAtTopK, double hitRateAt10, double mrr,
                        int budgetK, double hitRateAtBudget, List<String> missesAtTopK, long seconds) {}

    record ChunkSizeReport(String executedAt, String embeddingModel, int topK, int contextBudget,
                           List<ChunkSizeRun> runs) {}
}
