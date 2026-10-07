package com.example.aichat.eval;

import com.example.aichat.chat.RagProperties;
import com.example.aichat.document.IngestionProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("eval")
@SpringBootTest
class RetrievalEvaluationTest {
    private static final String DATASET = "eval/retrieval-dataset.json";
    private static final Path REPORT_DIR = Path.of("target", "eval");
    private static final String NO_ANSWER = "no-answer";
    private static final int MAX_K = 10;
    private static final List<Integer> KS = List.of(1, 3, 5, 10);
    private static final int EXCERPT_LENGTH = 160;
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00a0\\u2007\\u202f]+");

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
        var dataset = loadDataset();
        var indexed = loadIndexedChunks();

        var missingSources = dataset.stream()
                .map(EvalQuestion::source)
                .filter(Objects::nonNull)
                .filter(source -> indexed.stream().noneMatch(chunk -> source.equals(chunk.fileName())))
                .distinct()
                .toList();
        assertThat(missingSources).as("Documents referenced by the dataset that are not indexed").isEmpty();

        var results = dataset.stream()
                .map(question -> evaluate(question, isReachable(question, indexed)))
                .toList();
        var report = new RetrievalReport(config(dataset.size()), summarize(results), results);

        print(report);
        var reportFile = write(report);
        System.out.println("Report written to " + reportFile.toAbsolutePath());
    }

    private List<EvalQuestion> loadDataset() throws IOException {
        try (InputStream input = new ClassPathResource(DATASET).getInputStream()) {
            return jsonMapper.readValue(input, new TypeReference<List<EvalQuestion>>() {});
        }
    }

    private List<IndexedChunk> loadIndexedChunks() {
        return jdbcClient.sql("SELECT metadata->>'fileName' AS file_name, content FROM " + tableName)
                .query((rs, rowNum) -> new IndexedChunk(rs.getString("file_name"), normalize(rs.getString("content"))))
                .list();
    }

    private boolean isReachable(EvalQuestion question, List<IndexedChunk> indexed) {
        return question.evidence().stream()
                .map(RetrievalEvaluationTest::normalize)
                .anyMatch(evidence -> indexed.stream()
                        .anyMatch(chunk -> matchesSource(question, chunk.fileName()) && chunk.text().contains(evidence)));
    }

    private QuestionResult evaluate(EvalQuestion question, boolean reachable) {
        var documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question.question())
                .topK(MAX_K)
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .build());

        var hits = IntStream.range(0, documents.size())
                .mapToObj(index -> toHit(question, index + 1, documents.get(index)))
                .toList();

        Integer firstRelevantRank = hits.stream()
                .filter(Hit::relevant)
                .map(Hit::rank)
                .findFirst()
                .orElse(null);

        return new QuestionResult(question.id(), question.category(), question.question(), reachable,
                firstRelevantRank, hits);
    }

    private Hit toHit(EvalQuestion question, int rank, Document document) {
        var text = normalize(document.getText());
        var fileName = (String) document.getMetadata().get("fileName");
        var matched = matchesSource(question, fileName)
                ? question.evidence().stream().filter(evidence -> text.contains(normalize(evidence))).toList()
                : List.<String>of();
        return new Hit(rank, Objects.requireNonNullElse(document.getScore(), 0.0), !matched.isEmpty(), matched,
                fileName, excerpt(text));
    }

    private Summary summarize(List<QuestionResult> results) {
        var answerable = results.stream().filter(r -> !NO_ANSWER.equals(r.category()) && r.reachable()).toList();
        var noAnswer = results.stream().filter(r -> NO_ANSWER.equals(r.category())).toList();
        var unreachable = results.stream()
                .filter(r -> !NO_ANSWER.equals(r.category()) && !r.reachable())
                .map(QuestionResult::id)
                .toList();

        var hitRate = new LinkedHashMap<String, Double>();
        var precision = new LinkedHashMap<String, Double>();
        for (int k : KS) {
            hitRate.put("@" + k, mean(answerable, r -> r.firstRelevantRank() != null && r.firstRelevantRank() <= k ? 1 : 0));
            precision.put("@" + k, mean(answerable, r -> r.hits().stream().limit(k).filter(Hit::relevant).count() / (double) k));
        }

        var byCategory = new LinkedHashMap<String, Double>();
        answerable.stream().map(QuestionResult::category).distinct().forEach(category ->
                byCategory.put(category, mean(
                        answerable.stream().filter(r -> r.category().equals(category)).toList(),
                        r -> r.firstRelevantRank() != null && r.firstRelevantRank() <= ragProperties.topK() ? 1 : 0)));

        return new Summary(
                answerable.size(),
                noAnswer.size(),
                unreachable,
                hitRate,
                precision,
                mean(answerable, r -> r.firstRelevantRank() == null ? 0 : 1.0 / r.firstRelevantRank()),
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
                .filter(r -> !NO_ANSWER.equals(r.category()) && r.reachable())
                .filter(r -> r.firstRelevantRank() == null || r.firstRelevantRank() > config.topK())
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

    private Path write(RetrievalReport report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var file = REPORT_DIR.resolve("retrieval-%s.json".formatted(timestamp));
        Files.writeString(file, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return file;
    }

    private static boolean matchesSource(EvalQuestion question, String fileName) {
        return question.source() == null || question.source().equals(fileName);
    }

    private static double mean(List<QuestionResult> results, ToDoubleFunction<QuestionResult> metric) {
        return results.stream().mapToDouble(metric).average().orElse(0);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE.matcher(text.replace("­", "").toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static String excerpt(String text) {
        return text.length() <= EXCERPT_LENGTH ? text : text.substring(0, EXCERPT_LENGTH) + "…";
    }

    record EvalQuestion(String id, String category, String source, String question, List<String> evidence) {}

    record IndexedChunk(String fileName, String text) {}

    record Hit(int rank, double score, boolean relevant, List<String> matchedEvidence, String fileName, String excerpt) {}

    record QuestionResult(String id, String category, String question, boolean reachable,
                          Integer firstRelevantRank, List<Hit> hits) {}

    record Config(String executedAt, String embeddingModel, int chunkSize, int topK, double similarityThreshold,
                  String tableName, int datasetSize) {}

    record Summary(int answerable, int noAnswer, List<String> unreachable, Map<String, Double> hitRate,
                   Map<String, Double> precision, double mrr,
                   Map<String, Double> hitRateByCategory, double configHitRate, double configAverageChunks,
                   double configNoAnswerRejection, double averageRelevantScore, double averageNoAnswerTopScore) {}

    record RetrievalReport(Config config, Summary summary, List<QuestionResult> results) {}
}