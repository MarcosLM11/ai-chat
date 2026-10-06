2. Conversación y memoria

6. Un conversationId por conversación. Ahora todos los usuarios comparten una sola memoria. Pásalo por cabecera o en la ruta y úsalo en .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id)).
7. Memoria persistente. Usa JdbcChatMemoryRepository sobre el Postgres que ya tienes, para que las conversaciones sobrevivan a un reinicio.
8. Streaming. Con .stream().content() y Flux<String> sirves la respuesta por SSE, como hace ChatGPT.

3. Mejorar la calidad del RAG (lo más interesante para aprender)

10. Reescribir la consulta. RewriteQueryTransformer y CompressionQueryTransformer convierten "¿y eso cuánto cuesta?" en una pregunta autónoma usando el historial.
11. Multi-query. MultiQueryExpander genera varias versiones de la pregunta y une los resultados.
12. Búsqueda híbrida. Combina el full-text search de Postgres (tsvector, BM25-like) con la búsqueda vectorial y fusiona con Reciprocal Rank Fusion. Es muy didáctico.
13. Re-ranking. Recupera unos 20 chunks y reordénalos con un cross-encoder o con el propio LLM como juez antes de quedarte con 5.
14. Experimentar con el chunking. Prueba tamaños, overlap, división por secciones o títulos y chunking semántico, y mide cómo cambia la calidad.
15. Umbral de similitud con fallback. Si ningún chunk supera similarityThreshold, responde "no encuentro esto en los documentos" en lugar de inventar.
16. Ingesta enriquecida. KeywordMetadataEnricher y SummaryMetadataEnricher generan keywords y resúmenes por chunk con el LLM.

4. Tool calling y agentes

17. Tools con @Tool. Por ejemplo listDocuments(), getCurrentDate() o una búsqueda web. El modelo decide cuándo llamarlas.
18. RAG agéntico. Expón la búsqueda vectorial como tool en vez de usarla como advisor: el modelo decide si buscar, qué buscar y si repetir la búsqueda.
19. MCP. Expón tu RAG como servidor MCP (spring-ai-starter-mcp-server) y úsalo desde Claude Desktop o Claude Code, o consume servidores MCP externos.
20. Salida estructurada. Usa .entity(MiRecord.class), por ejemplo para extraer datos de una factura subida como PDF.

5. Evaluación y observabilidad

21. Evaluar el RAG. Monta un dataset de preguntas y respuestas sobre tus documentos y usa RelevancyEvaluator y FactCheckingEvaluator de Spring AI. Así sabrás si un cambio de chunking mejora o empeora.
22. Observabilidad. Spring AI emite métricas y trazas con Micrometer. Conéctalas a Zipkin/Jaeger y Prometheus/Grafana con docker-compose para ver tokens, latencia y coste por petición.
23. Advisor propio. Escribe un CallAdvisor que registre los tokens usados o bloquee prompt injection o PII. Así entiendes la cadena de advisors por dentro.

6. Producto y robustez

24. Ingesta asíncrona. Con documentos grandes, sube y devuelve 202 Accepted con un estado (PROCESSING/READY/FAILED) usando @Async o virtual threads.
25. Evitar duplicados. Calcula un hash SHA-256 del archivo y no reindexes si ya existe.
26. Varios proveedores. Cambia entre OpenAI, Anthropic (Claude) y Ollama local por configuración y compara calidad y coste.
27. Multimodal. Sube imágenes o PDFs escaneados y descríbelos con un modelo de visión antes de indexar.
28. Frontend sencillo. Una página con streaming, lista de documentos y fuentes clicables.
29. Seguridad multiusuario. Con Spring Security, cada usuario ve solo sus documentos (filtro por ownerId en los metadatos) y sus conversaciones.
30. Tests de integración. Testcontainers con pgvector, más un modelo simulado para que los tests no consuman la API.