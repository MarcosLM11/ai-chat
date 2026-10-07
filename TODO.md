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

26. Varios proveedores. Cambia entre OpenAI, Anthropic (Claude) y Ollama local por configuración y compara calidad y coste.
27. Multimodal. Sube imágenes o PDFs escaneados y descríbelos con un modelo de visión antes de indexar. Ahora esos documentos acaban en FAILED con "No text could be extracted from the document".
28. Frontend sencillo. Ya hay streaming, fuentes clicables y panel de documentos con subida, borrado y estado. Falta elegir en el chat qué documentos usar (documentIds) y permitir marcar solo los que están en READY.
29. Seguridad multiusuario. Con Spring Security, cada usuario ve solo sus documentos (filtro por ownerId en los metadatos) y sus conversaciones. La restricción única documents_content_hash_key es global y debería pasar a ser (owner_id, content_hash).
30. Tests de integración. Testcontainers con pgvector, más un modelo simulado para que los tests no consuman la API. Como mínimo: subir un duplicado devuelve 409, una ingesta correcta acaba en READY, un fallo acaba en FAILED sin chunks y borrar un documento mientras se procesa no deja chunks huérfanos.

7. Ingesta asíncrona

31. Recuperar documentos sin procesar tras un reinicio. La subida guarda el documento en PENDING y publica un DocumentUploadedEvent, que DocumentIngestionService procesa con @Async después del commit. La cola del executor vive en memoria, así que si la aplicación se para o se cae, los documentos en cola se quedan en PENDING para siempre y los que se estaban procesando se quedan en PROCESSING. La ingesta ya es idempotente (borra los chunks del documento antes de indexar), así que reprocesarlos no duplica nada. Opciones:
    - Reencolar al arrancar. Un listener de ApplicationReadyEvent busca los documentos en PENDING o PROCESSING y vuelve a publicar el evento. Es lo más sencillo, pero solo funciona con una instancia: con varias, todas reencolarían los mismos documentos.
    - Usar la base de datos como cola. Se eliminan el evento y @Async, y un @Scheduled reclama trabajo con SELECT ... WHERE status = 'PENDING' ... FOR UPDATE SKIP LOCKED. No se pierde nada al reiniciar y funciona con varias instancias. Los documentos en PROCESSING necesitan un timeout (por ejemplo, una columna processing_started_at) que los devuelva a PENDING.
    - Spring Modulith Event Publication Registry (spring-modulith-events-jpa). Guarda cada evento en una tabla hasta que el listener termina bien (patrón outbox) y vuelve a publicar los pendientes al arrancar (spring.modulith.events.republish-outstanding-events-on-restart). Mantiene el diseño actual casi sin cambios.
    Recomendación: Modulith encaja con el diseño actual. Si algún día hay varias instancias, la base de datos como cola.
32. Reprocesar documentos en FAILED. Ahora hay que borrarlos y volver a subirlos, porque la deduplicación por SHA-256 bloquea la nueva subida. Se podría añadir POST /api/v1/documents/{id}/reprocess.
33. Mensajes de error de la API. Spring Boot no incluye el reason de ResponseStatusException en la respuesta y el frontend muestra textos fijos según el código HTTP. Activar spring.mvc.problemdetails.enabled permitiría mostrar, por ejemplo, el id del documento duplicado.
34. Límite de tamaño en un solo sitio. Los 40 MB están en application.yaml (spring.servlet.multipart.max-file-size) y repetidos en index.html (MAX_FILE_SIZE_BYTES). Se podría exponer el límite desde la API o servirlo junto a la página.