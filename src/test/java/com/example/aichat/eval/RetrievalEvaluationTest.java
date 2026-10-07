package com.example.aichat.eval;

import com.example.aichat.chat.RagProperties;
import com.example.aichat.document.IngestionProperties;
import com.example.aichat.eval.RetrievalEvaluator.EvalQuestion;
import com.example.aichat.eval.RetrievalEvaluator.Hit;
import com.example.aichat.eval.RetrievalEvaluator.IndexedChunk;
import com.example.aichat.eval.RetrievalEvaluator.QuestionResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.example.aichat.eval.RetrievalEvaluator.hitRate;
import static com.example.aichat.eval.RetrievalEvaluator.isAnswerable;
import static com.example.aichat.eval.RetrievalEvaluator.isHit;
import static com.example.aichat.eval.RetrievalEvaluator.mean;
import static com.example.aichat.eval.RetrievalEvaluator.mrr;
import static com.example.aichat.eval.RetrievalEvaluator.normalize;
import static com.example.aichat.eval.RetrievalEvaluator.precision;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("eval")
@SpringBootTest
class RetrievalEvaluationTest {
    private static final int MAX_K = 10;
    private static final List<Integer> KS = List.of(1, 3, 5, 10);

    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private RagProperties ragProperties;
    @Autowired
    private IngestionProperties ingestionProperties;

    @Value("${spring.ai.ollama.embedding.model}")
    private String embeddingModel;
    @Value("${spring.ai.vectorstore.pgvector.table-name}")
    private String tableName;

    @Test
    void evaluateRetrieval() throws IOException {
        var dataset = RetrievalEvaluator.loadDataset(jsonMapper);
        var indexed = loadIndexedChunks();

        var missingSources = dataset.stream()
                .map(EvalQuestion::source)
                .filter(Objects::nonNull)
                .filter(source -> indexed.stream().noneMatch(chunk -> source.equals(chunk.fileName())))
                .distinct()
                .toList();
        assertThat(missingSources).as("Documents referenced by the dataset that are not indexed").isEmpty();

        var results = RetrievalEvaluator.evaluate(dataset, vectorStore, indexed, MAX_K);
        var report = new RetrievalReport(config(dataset.size()), summarize(results), results);

        print(report);
        var reportFile = RetrievalEvaluator.writeReport(jsonMapper, "retrieval", report);
        System.out.println("Report written to " + reportFile.toAbsolutePath());
    }

    private List<IndexedChunk> loadIndexedChunks() {
        return jdbcClient.sql("SELECT metadata->>'fileName' AS file_name, content FROM " + tableName)
                .query((rs, rowNum) -> new IndexedChunk(rs.getString("file_name"), normalize(rs.getString("content"))))
                .list();
    }

    private Summary summarize(List<QuestionResult> results) {
        var answerable = results.stream().filter(r -> isAnswerable(r) && r.reachable()).toList();
        var noAnswer = results.stream().filter(r -> !isAnswerable(r)).toList();
        var unreachable = results.stream()
                .filter(r -> isAnswerable(r) && !r.reachable())
                .map(QuestionResult::id)
                .toList();

        var hitRates = new LinkedHashMap<String, Double>();
        var precisions = new LinkedHashMap<String, Double>();
        for (int k : KS) {
            hitRates.put("@" + k, hitRate(answerable, k));
            precisions.put("@" + k, precision(answerable, k));
        }

        var byCategory = new LinkedHashMap<String, Double>();
        answerable.stream().map(QuestionResult::category).distinct().forEach(category ->
                byCategory.put(category, hitRate(
                        answerable.stream().filter(r -> r.category().equals(category)).toList(),
                        ragProperties.topK())));

        return new Summary(
                answerable.size(),
                noAnswer.size(),
                unreachable,
                hitRates,
                precisions,
                mrr(answerable),
                byCategory,
                mean(answerable, r -> withConfig(r).stream().anyMatch(Hit::relevant) ? 1 : 0),
                mean(answerable, r -> withConfig(r).size()),
                mean(noAnswer, r -> withConfig(r).isEmpty() ? 1 : 0),
                mean(answerable.stream().filter(r -> r.firstRelevantRank() != null).toList(),
                        r -> r.hits().get(r.firstRelevantRank() - 1).score()),
                mean(noAnswer, r -> r.hits().isEmpty() ? 0 : r.hits().getFirst().score())
        );
    }

    private List<Hit> withConfig(QuestionResult result) {
        return result.hits().stream()
                .filter(hit -> hit.score() >= ragProperties.similarityThreshold())
                .limit(ragProperties.topK())
                .toList();
    }

    private Config config(int datasetSize) {
        return new Config(LocalDateTime.now().toString(), embeddingModel, ingestionProperties.chunkSize(),
                ragProperties.topK(), ragProperties.similarityThreshold(), tableName, datasetSize);
    }

    private void print(RetrievalReport report) {
        var config = report.config();
        var summary = report.summary();
        var out = new StringBuilder();
        out.append("%n=== Retrieval evaluation ===%n".formatted());
        out.append("Embedding model: %s | chunk-size: %d | top-k: %d | similarity-threshold: %.2f%n".formatted(
                config.embeddingModel(), config.chunkSize(), config.topK(), config.similarityThreshold()));
        out.append("Questions: %d answerable, %d no-answer%n".formatted(summary.answerable(), summary.noAnswer()));
        if (!summary.unreachable().isEmpty()) {
            out.append("WARNING: evidence not found in any indexed chunk (excluded): %s%n".formatted(summary.unreachable()));
        }
        out.append("%n%-12s%s%n".formatted("", KS.stream().map(k -> "%8s".formatted("@" + k)).reduce("", String::concat)));
        appendRow(out, "Hit rate", summary.hitRate());
        appendRow(out, "Precision", summary.precision());
        out.append("%-12s%8.3f%n".formatted("MRR@" + MAX_K, summary.mrr()));
        out.append("%nHit rate@%d by category: %s%n".formatted(config.topK(), format(summary.hitRateByCategory())));
        out.append("%nWith the app configuration (top-k %d, threshold %.2f):%n".formatted(config.topK(), config.similarityThreshold()));
        out.append("  Answerable questions with a relevant chunk: %.3f%n".formatted(summary.configHitRate()));
        out.append("  Average chunks passed to the model:         %.2f%n".formatted(summary.configAverageChunks()));
        out.append("  No-answer questions with no chunks:          %.3f%n".formatted(summary.configNoAnswerRejection()));
        out.append("%nScore of first relevant chunk (answerable): %.3f%n".formatted(summary.averageRelevantScore()));
        out.append("Score of top chunk (no-answer):             %.3f%n".formatted(summary.averageNoAnswerTopScore()));
        out.append("%nMisses (no relevant chunk in top %d):%n".formatted(config.topK()));
        report.results().stream()
                .filter(r -> isAnswerable(r) && r.reachable())
                .filter(r -> !isHit(r, config.topK()))
                .forEach(r -> out.append("  %s (rank %s) %s%n".formatted(
                        r.id(), r.firstRelevantRank() == null ? "-" : r.firstRelevantRank(), r.question())));
        System.out.println(out);
    }

    private void appendRow(StringBuilder out, String label, Map<String, Double> values) {
        out.append("%-12s".formatted(label));
        values.values().forEach(value -> out.append("%8.3f".formatted(value)));
        out.append(System.lineSeparator());
    }

    private String format(Map<String, Double> values) {
        return values.entrySet().stream()
                .map(entry -> "%s %.3f".formatted(entry.getKey(), entry.getValue()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("-");
    }

    record Config(String executedAt, String embeddingModel, int chunkSize, int topK, double similarityThreshold,
                  String tableName, int datasetSize) {}

    record Summary(int answerable, int noAnswer, List<String> unreachable, Map<String, Double> hitRate,
                   Map<String, Double> precision, double mrr,
                   Map<String, Double> hitRateByCategory, double configHitRate, double configAverageChunks,
                   double configNoAnswerRejection, double averageRelevantScore, double averageNoAnswerTopScore) {}

    record RetrievalReport(Config config, Summary summary, List<QuestionResult> results) {}
}
