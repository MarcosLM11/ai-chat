package com.example.aichat.eval;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

final class RetrievalEvaluator {
    static final String NO_ANSWER = "no-answer";

    private static final String DATASET = "eval/retrieval-dataset.json";
    private static final Path REPORT_DIR = Path.of("target", "eval");
    private static final int EXCERPT_LENGTH = 160;
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00a0\\u2007\\u202f]+");

    private RetrievalEvaluator() {}

    static List<EvalQuestion> loadDataset(JsonMapper jsonMapper) throws IOException {
        try (InputStream input = new ClassPathResource(DATASET).getInputStream()) {
            return jsonMapper.readValue(input, new TypeReference<List<EvalQuestion>>() {});
        }
    }

    static List<QuestionResult> evaluate(List<EvalQuestion> dataset, VectorStore vectorStore,
                                         List<IndexedChunk> indexed, int maxK) {
        return dataset.stream()
                .map(question -> evaluate(question, vectorStore, isReachable(question, indexed), maxK))
                .toList();
    }

    static boolean isAnswerable(QuestionResult result) {
        return !NO_ANSWER.equals(result.category());
    }

    static boolean isHit(QuestionResult result, int k) {
        return result.firstRelevantRank() != null && result.firstRelevantRank() <= k;
    }

    static double hitRate(List<QuestionResult> results, int k) {
        return mean(results, r -> isHit(r, k) ? 1 : 0);
    }

    static double precision(List<QuestionResult> results, int k) {
        return mean(results, r -> r.hits().stream().limit(k).filter(Hit::relevant).count() / (double) k);
    }

    static double mrr(List<QuestionResult> results) {
        return mean(results, r -> r.firstRelevantRank() == null ? 0 : 1.0 / r.firstRelevantRank());
    }

    static double mean(List<QuestionResult> results, ToDoubleFunction<QuestionResult> metric) {
        return results.stream().mapToDouble(metric).average().orElse(0);
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE.matcher(text.replace("­", "").toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    static Path writeReport(JsonMapper jsonMapper, String prefix, Object report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var file = REPORT_DIR.resolve("%s-%s.json".formatted(prefix, timestamp));
        Files.writeString(file, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return file;
    }

    private static boolean isReachable(EvalQuestion question, List<IndexedChunk> indexed) {
        return question.evidence().stream()
                .map(RetrievalEvaluator::normalize)
                .anyMatch(evidence -> indexed.stream()
                        .anyMatch(chunk -> matchesSource(question, chunk.fileName()) && chunk.text().contains(evidence)));
    }

    private static QuestionResult evaluate(EvalQuestion question, VectorStore vectorStore, boolean reachable, int maxK) {
        var documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question.question())
                .topK(maxK)
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

    private static Hit toHit(EvalQuestion question, int rank, Document document) {
        var text = normalize(document.getText());
        var fileName = (String) document.getMetadata().get("fileName");
        var matched = matchesSource(question, fileName)
                ? question.evidence().stream().filter(evidence -> text.contains(normalize(evidence))).toList()
                : List.<String>of();
        return new Hit(rank, Objects.requireNonNullElse(document.getScore(), 0.0), !matched.isEmpty(), matched,
                fileName, excerpt(text));
    }

    private static boolean matchesSource(EvalQuestion question, String fileName) {
        return question.source() == null || question.source().equals(fileName);
    }

    private static String excerpt(String text) {
        return text.length() <= EXCERPT_LENGTH ? text : text.substring(0, EXCERPT_LENGTH) + "…";
    }

    record EvalQuestion(String id, String category, String source, String question, List<String> evidence) {}

    record IndexedChunk(String fileName, String text) {}

    record Hit(int rank, double score, boolean relevant, List<String> matchedEvidence, String fileName, String excerpt) {}

    record QuestionResult(String id, String category, String question, boolean reachable,
                          Integer firstRelevantRank, List<Hit> hits) {}
}
