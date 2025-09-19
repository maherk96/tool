# Spring Load Task Service

Spring Boot service that queues and simulates execution of load-testing tasks. It exposes a REST API that lets clients submit work items, inspect task state and metrics, cancel jobs, and review execution history without needing real downstream systems.

## Features
- Submit load tasks with typed payloads (`REST_LOAD`, `FIX_LOAD`, `GRPC_LOAD`, `WEBSOCKET_LOAD`, `CUSTOM`).
- Track lifecycle state (`QUEUED`, `PROCESSING`, `COMPLETED`, `ERROR`, `CANCELLED`) for each task.
- Cancel tasks while they are queued or actively processing.
- Observe aggregate metrics, queue depth, supported task types, and health information.
- Persist a bounded task history for recent activity inspection.
- Simulate REST protocols through configurable open/closed load models, think time strategies, and per-request definitions.

## How It Works
- **API layer** – `LoadTaskController` under `/api/tasks` validates submissions, exposes status surfaces, and maps JSON payloads into domain tasks.
- **Task service** – `LoadTaskService` keeps a worker pool, tracks active futures for cancellation, maintains lifecycle history, and aggregates metrics.
- **Processors** – `LoadTaskProcessor` implementations execute protocol-specific workloads. The bundled `RestLoadTaskProcessor` drives HTTP requests using configurable load models backed by the `OpenLoadExecutor` and `ClosedLoadExecutor` utilities.
- **HTTP client** – `LoadHttpClient` wraps Java's `HttpClient`, adding header/variable resolution, per-request logging, and response timing capture for each synthetic call.

## Requirements
- Java 21+
- Gradle (wrapper included)

## Getting Started
```bash
./gradlew bootRun
```

The service starts on `http://localhost:8080` and serves the REST endpoints under `/api/tasks`.

Submit a REST load task:

```bash
curl -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{
        "taskType": "REST_LOAD",
        "data": {
          "testSpec": {
            "globalConfig": {
              "baseUrl": "https://httpbin.org",
              "headers": {"X-Test": "spring-load"}
            },
            "scenarios": [
              {
                "name": "smoke",
                "requests": [
                  {"method": "GET", "path": "/get"}
                ]
              }
            ]
          },
          "execution": {
            "loadModel": {"type": "OPEN", "arrivalRatePerSec": 1, "maxConcurrent": 2, "duration": "30s"},
            "thinkTime": {"type": "NONE"}
          }
        }
      }'
```

The response returns a generated `taskId`. Use the status endpoint to poll progress or the cancellation endpoint to stop the run.

## Key Endpoints
- `POST /api/tasks` – Submit a new task; rejects invalid payloads and duplicate IDs.
- `GET /api/tasks/{taskId}` – Retrieve detailed status for a specific task.
- `GET /api/tasks` – List all tasks, optionally filtered by `status` query parameter.
- `DELETE /api/tasks/{taskId}` – Request cancellation for a queued or running task.
- `GET /api/tasks/history` – Fetch recent task history (bounded by configured history size).
- `GET /api/tasks/queue` – Inspect queue depth and worker activity.
- `GET /api/tasks/metrics` – View aggregate success, failure, and cancellation statistics.
- `GET /api/tasks/types` – Enumerate supported task types.
- `GET /api/tasks/health` – Lightweight health probe.

## Configuration
The `TaskProcessingProperties` (`load.tasks.*`) control executor behaviour:
- `concurrency` – Worker pool size (default `1`).
- `historySize` – Maximum number of entries retained in task history (default `50`).
- `simulatedDurationMs` – Sleep duration used to emulate task execution (default `1000`).

These values can be overridden via `application.yml`/`application.properties` when running the service.

Example snippet (`src/main/resources/application.yaml`):

```yaml
load:
  tasks:
    concurrency: 4
    history-size: 100
    simulated-duration-ms: 500
```

If you need to disable new submissions (for maintenance, drain, etc.) invoke `/api/tasks/queue` to observe worker state and send cancellation requests for active tasks. The service stops accepting work when `LoadTaskService#shutdown` is called (e.g., on application stop).

## Testing
Execute the full test suite:
```bash
./gradlew test
```

Tests include a comprehensive unit pack for `LoadTaskService`, covering submission flows, cancellation paths, metrics, queue state, and history trimming.

Additional suites exercise the REST processor and load executors to ensure open/closed model scheduling, cancellation signals, and HTTP orchestration behave as expected.

## Purpose
Use this service as a controllable harness for experimenting with load-task orchestration. It provides a predictable environment to prototype task submission APIs, cancellation semantics, and operational metrics without needing real downstream integrations.
