# Spring Load Task Service

Spring Boot service that queues and simulates execution of load-testing tasks. It exposes a REST API that lets clients submit work items, inspect task status and metrics, cancel jobs, and review recent execution history.

## Features
- Submit load tasks with typed payloads (`REST`, `FIX`, `CUSTOM`).
- Track lifecycle state (`QUEUED`, `PROCESSING`, `COMPLETED`, `ERROR`, `CANCELLED`) for each task.
- Cancel tasks while they are queued or actively processing.
- Observe aggregate metrics, queue depth, supported task types, and health information.
- Persist a bounded task history for recent activity inspection.

## Requirements
- Java 21+
- Gradle (wrapper included)

## Getting Started
```bash
./gradlew bootRun
```

The service starts on `http://localhost:8080` and serves the REST endpoints under `/api/tasks`.

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

## Testing
Execute the full test suite:
```bash
./gradlew test
```

Tests include a comprehensive unit pack for `LoadTaskService`, covering submission flows, cancellation paths, metrics, queue state, and history trimming.

## Purpose
Use this service as a controllable harness for experimenting with load-task orchestration. It provides a predictable environment to prototype task submission APIs, cancellation semantics, and operational metrics without needing real downstream integrations.

