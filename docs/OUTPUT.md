# Выгрузки и API

results/result.geojson — все три варианта, GeoJSON FeatureCollection, WGS84. best-variant.geojson — вариант с rank=1. Храните вместе с data/corrected.geojson: концы линий могут ссылаться на существующие камеры и точки входного файла.

| object_type | Основные поля |
|---|---|
| heat_network | start_node_id, end_node_id, flow_tph, diameter, length, laying_method, depth_start/end, cost |
| heat_chamber | diameter по максимальному примыканию, cost |
| technical_node | узел смены параметров без ветвления |
| variant_summary | rank, construction_cost, chamber_construction_cost, existing_chamber_tie_in_count/cost, unconnected_penalty, calculated_cost, new_network_length, score, unconnected_oks_ids |

Обычный поворот — внутренняя вершина LineString. Границы спецпрохода делят участки. В 2D глубины равны null. Реконструкция не выполняется по актуальному приложению. result.report.json хранит состояние, протокол, SHA-256 входа и fingerprints. summary.json содержит компактные показатели.

| Запрос | Назначение |
|---|---|
| GET /api/v1/capabilities | возможности и лимиты |
| GET /api/v1/catalog | каталог диаметров |
| POST /api/v1/datasets/audit | проверка файла, multipart file |
| POST /api/v1/calculations | загрузка файла, multipart file; HTTP 201 и UUID |
| POST /api/v1/calculations/{id}/run | запуск в очереди, HTTP 202 |
| GET /api/v1/calculations/{id} | статус и отчёт |
| GET /api/v1/calculations/{id}/result | потоковая выгрузка |
| GET /api/v1/calculations | последние 100 заданий |
| GET /actuator/health | работоспособность |

Swagger: /swagger-ui.html; OpenAPI: /v3/api-docs и api/openapi.json. Ошибки: 404 — неизвестное задание, 409 — статус или очередь, 413 — лимит multipart, 422 — некорректные данные. Деталь возвращается в JSON.
